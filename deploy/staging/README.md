# Staging (server on-prem, acceso solo por Tailscale)

Ambiente de prueba que corre en el server on-prem (el mismo que quedó de
rollback tras el cutover a Azure). Cada push a `develop` lo redeploya vía un
runner self-hosted de GitHub Actions; su DB es una **copia real de prod**
restaurada del backup diario, de modo que **toda migración Flyway se prueba
contra prod-de-verdad antes de llegar a `main`**.

```
push a develop ──► runner self-hosted ──► build imágenes ──► compose up
                                                │
db-staging ◄── restore-staging-db.sh ◄── dump diario 02:00 UTC (OneDrive)
```

- Frontend: `http://<ip-tailscale>:8088/cotizaciones/`
- La DB **no** se refresca en cada deploy: se refresca a demanda con
  `restore-staging-db.sh` (típicamente antes de probar una migración nueva).

## Reglas de seguridad (repo PÚBLICO — no negociable)

1. El runner y staging son alcanzables **solo por Tailscale**; nada se
   publica a internet (`STAGING_BIND_IP` = IP de Tailscale).
2. El workflow se dispara **solo** con `push` a `develop` (+ manual).
   **NUNCA** agregar `pull_request` en runners self-hosted: un fork podría
   ejecutar código arbitrario en el server.
3. En GitHub → Settings → Actions → General: *Require approval for all
   external contributors*. El runner lleva label dedicado `staging-canada`
   y los jobs lo referencian explícito.
4. Los valores reales (IPs, passwords, claves) viven **solo** en el server:
   `/opt/tms-staging/staging.env` + `/opt/tms-staging/keys/`. Cero GitHub
   Secrets, cero valores reales en el repo.
5. El runner corre como usuario **sin sudo**, solo con acceso al socket de
   Docker.

## Instalación (una vez, en el server)

### 1. Preparar carpeta, env file y claves JWT de staging

```bash
# Clonar el repo (los comandos siguientes asumen cwd = raíz del repo)
git clone https://github.com/ascaramutti/scaramutti-tms-v2.git
cd scaramutti-tms-v2

sudo mkdir -p /opt/tms-staging/keys
sudo chown -R "$USER" /opt/tms-staging

# Env file (completar los valores reales a mano)
cp deploy/staging/staging.env.example /opt/tms-staging/staging.env
chmod 600 /opt/tms-staging/staging.env

# Claves JWT PROPIAS de staging (no reutilizar las de prod)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out /opt/tms-staging/keys/privateKey.pem
openssl rsa -pubout -in /opt/tms-staging/keys/privateKey.pem \
  -out /opt/tms-staging/keys/publicKey.pem
chmod 600 /opt/tms-staging/keys/privateKey.pem

# El backend corre como el usuario `quarkus` (uid 1001) dentro del
# contenedor: la clave privada debe pertenecerle o el login da 500
sudo chown 1001 /opt/tms-staging/keys/privateKey.pem
chmod 644 /opt/tms-staging/keys/publicKey.pem
```

### 2. Instalar el runner self-hosted

En GitHub: repo → Settings → Actions → Runners → *New self-hosted runner*
(Linux x64) y seguir los comandos que muestra (bajan el tarball con un token
de registro de un solo uso). En la configuración:

- **Labels**: agregar `staging-canada` (además de los default).
- Instalarlo como servicio para que sobreviva reinicios:

```bash
cd ~/actions-runner
sudo ./svc.sh install "$USER"   # única acción con sudo (registra el systemd unit)
sudo ./svc.sh start
```

Verificar en Settings → Actions → Runners que aparece *Idle* con el label
`staging-canada`.

### 3. Primer arranque

```bash
# Levantar los 3 contenedores (la primera vez la DB nace vacía y Flyway
# construye el schema desde V001; el restore de abajo la reemplaza)
docker compose --env-file /opt/tms-staging/staging.env \
  -f deploy/staging/docker-compose.staging.yml up -d --build

# Cargar la copia real de prod (dump más reciente del cron 02:00 UTC)
./deploy/staging/restore-staging-db.sh
```

> El script **aborta** si el dump no trae `flyway_schema_history` (dumps
> anteriores al baseline de prod, < 2026-07-08, no sirven: el backend corre
> Flyway estricto y no arrancaría).

### 4. Verificación end-to-end

Push (o merge) a `develop` → pestaña Actions → *Deploy staging* debe quedar
verde → abrir `http://<ip-tailscale>:8088/cotizaciones/` y loguearse (las
credenciales son las de prod: la DB es una copia).

## Operación

| Qué | Cómo |
|---|---|
| Refrescar la DB con el prod de anoche | `./deploy/staging/restore-staging-db.sh` |
| Restaurar un dump puntual | `./deploy/staging/restore-staging-db.sh tms_azure_YYYYMMDD_HHMMSS.sql.gz` |
| Redeploy manual sin push | Actions → *Deploy staging* → *Run workflow* |
| Logs del backend | `docker logs -f scaramutti-tms-v2-backend-staging` |
| Estado de Flyway en staging | `docker exec scaramutti-tms-v2-db-staging psql -U <db-username> -d scaramutti_tms_staging -c 'TABLE public.flyway_schema_history;'` |
