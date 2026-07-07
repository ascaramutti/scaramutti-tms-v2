#!/bin/bash
# ==========================================
# Refresca la DB de STAGING con el backup diario de prod.
# Toma el dump más reciente del cron de las 02:00 UTC (OneDrive), lo
# restaura en db-staging y reinicia el backend para que Flyway aplique
# las migraciones NUEVAS encima de la copia real de prod.
#
# Corre en el server de staging como el usuario dueño del remote de
# rclone (no root). Uso:
#   ./restore-staging-db.sh [archivo.sql.gz]
# Sin argumento restaura el dump más reciente; con argumento, ese dump
# puntual del remote (p.ej. para reproducir un estado anterior).
# ==========================================

set -euo pipefail
# El dump es data real de prod: que ningún archivo temporal quede legible
# para otros usuarios del server
umask 077

# CONFIGURACIÓN (sobreescribible por env)
ENV_FILE="${ENV_FILE:-/opt/tms-staging/staging.env}"
COMPOSE_FILE="$(cd "$(dirname "$0")" && pwd)/docker-compose.staging.yml"
ONEDRIVE_REMOTE="${ONEDRIVE_REMOTE:-onedrive:tms-backups}"
PREFIX="${PREFIX:-tms_azure_}"
WORK_DIR="${WORK_DIR:-/opt/tms-staging/restore-tmp}"
DB_CONTAINER="scaramutti-tms-v2-db-staging"
BACKEND_SERVICE="backend-staging"

compose() {
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

# El dump descomprimido no debe sobrevivir al script (éxito o fallo)
DUMP_SQL=""
trap '[ -n "$DUMP_SQL" ] && rm -f "$DUMP_SQL"' EXIT
trap 'log "FALLO: el backend puede quedar parado y la DB a medias — re-ejecutar este script lo repara (recrea la DB desde cero)"' ERR

# El env file trae DB_USERNAME/STAGING_DB_NAME (mismas vars que el compose).
# DB_USERNAME debe ser el rol OWNER de los objetos del dump (ver README).
# shellcheck disable=SC1090
source "$ENV_FILE"
DB_NAME="${STAGING_DB_NAME:-scaramutti_tms_staging}"

# Los dos se interpolan en SQL sin comillas de identificador: solo
# minúsculas/dígitos/guión bajo
for v in "$DB_NAME" "$DB_USERNAME"; do
    if ! [[ "$v" =~ ^[a-z_][a-z0-9_]*$ ]]; then
        log "ERROR: identificador inválido en el env file: '$v'"
        exit 1
    fi
done

# 1. Ubicar el dump (el más reciente, o el pedido por argumento)
if [ $# -ge 1 ]; then
    DUMP_GZ="$1"
else
    DUMP_GZ=$(rclone lsf "$ONEDRIVE_REMOTE" | grep "^${PREFIX}" | sort | tail -n 1 || true)
fi
if [ -z "$DUMP_GZ" ]; then
    log "ERROR: no hay dumps con prefijo ${PREFIX} en ${ONEDRIVE_REMOTE}"
    exit 1
fi
log "Dump elegido: $DUMP_GZ"

# 2. Bajarlo y descomprimirlo
mkdir -p "$WORK_DIR"
rclone copy "$ONEDRIVE_REMOTE/$DUMP_GZ" "$WORK_DIR/"
DUMP_SQL="$WORK_DIR/${DUMP_GZ%.gz}"
gunzip -f "$WORK_DIR/$DUMP_GZ"
log "Descargado y descomprimido: $(du -h "$DUMP_SQL" | cut -f1)"

# 3. Guarda: el dump debe traer la historia de Flyway. El backend corre
#    estricto (sin baseline-on-migrate): una DB no-vacía sin
#    flyway_schema_history lo haría fallar al arrancar. Los dumps
#    anteriores al baseline de prod (< 2026-07-08) no sirven.
if ! grep -q "flyway_schema_history" "$DUMP_SQL"; then
    log "ERROR: el dump NO trae flyway_schema_history (¿anterior al baseline de prod?). Aborto sin tocar staging."
    exit 1
fi
log "Guarda OK: el dump trae flyway_schema_history"

# 4. Parar el backend (nada conectado a la DB durante el restore)
compose stop "$BACKEND_SERVICE"

# 5. Recrear la DB desde cero
docker exec "$DB_CONTAINER" psql -U "$DB_USERNAME" -d postgres -v ON_ERROR_STOP=1 \
    -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DB_NAME' AND pid <> pg_backend_pid();" \
    -c "DROP DATABASE IF EXISTS $DB_NAME;" \
    -c "CREATE DATABASE $DB_NAME OWNER $DB_USERNAME;"
log "DB $DB_NAME recreada"

# 6. Restaurar el dump
docker exec -i "$DB_CONTAINER" psql -U "$DB_USERNAME" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 --quiet < "$DUMP_SQL"
log "Dump restaurado"

# 7. Arrancar el backend → Flyway valida la historia y aplica lo nuevo
compose up -d "$BACKEND_SERVICE"

# 8. Esperar el healthcheck del backend (start_period 60s + margen)
log "Esperando healthcheck del backend..."
for _ in $(seq 1 30); do
    STATUS=$(docker inspect --format '{{.State.Health.Status}}' scaramutti-tms-v2-backend-staging 2>/dev/null || echo "starting")
    if [ "$STATUS" = "healthy" ]; then
        log "Backend healthy — staging refrescado con $DUMP_GZ"
        exit 0
    fi
    sleep 5
done

log "ERROR: el backend no llegó a healthy tras el restore. Revisar: docker logs scaramutti-tms-v2-backend-staging"
exit 1
