# Base de datos

> **⚠️ La verdad del schema se mudó a Flyway**: `backend/src/main/resources/db/migration/`
> (`V001__baseline.sql` = foto de prod al 2026-07-06 + migraciones incrementales). El backend
> las aplica al arrancar. Ver el `README.md` de esa carpeta para las reglas de la cadena.

## Contenido de esta carpeta

- **`baseline.sql`** — **HISTÓRICO** (snapshot pre-Flyway, consolidado hasta 2026-06-19).
  Ya no se aplica a mano: una DB nueva se provisiona arrancando el backend (Flyway ejecuta
  `V001` completo sobre una DB vacía). Se conserva como referencia.
- **`patches/`** — **HISTÓRICO**. Parches aplicados manualmente a prod antes de Flyway; ya
  están incorporados en `V001__baseline.sql`. No aplicar de nuevo.
- **`seed_system_settings.sql`** — VIGENTE. Datos **reales** de la empresa emisora que usa el
  PDF de cotización (razón social, cuentas bancarias, términos). Fuera de la cadena Flyway
  (son datos, no schema); se aplica manual, es idempotente (`ON CONFLICT DO NOTHING`).

## Setup de desarrollo

1. Levantar la DB (`docker compose up -d db` del compose de v1, o el contenedor
   `scaramutti-tms-db-dev` existente).
2. **Arrancar el backend** (`mvn quarkus:dev`): Flyway crea/baselinea el schema y el
   `DevDataSeeder` siembra los fixtures de desarrollo (usuarios, monedas, términos de pago,
   tipos de servicio). Solo corre en dev/test (`@UnlessBuildProfile("prod")`), nunca en prod.
3. `psql ... -f db/seed_system_settings.sql` — sembrar los datos de empresa (los tests de
   integración y el PDF los usan; sin ellos el PDF degrada a vacío, no crashea).

## Producción

Flyway corre al arrancar el backend: en la DB existente solo marca el baseline (V001, cero
DDL) y aplica las migraciones nuevas que vengan en el release. `seed_system_settings.sql` ya
está aplicado; los datos de `system_settings` se editan sin redeploy con un `UPDATE` puntual.
