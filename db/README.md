# Base de datos — orden de provisión

No hay Flyway/Liquibase (`quarkus.hibernate-orm.database.generation=none`): estos scripts se
aplican **manualmente**, en orden, sobre una BD nueva.

## Setup de desarrollo

1. **`baseline.sql`** — schema consolidado (tablas, índices, constraints, schema `cotizaciones`).
2. **`seed_system_settings.sql`** — datos **reales** de la empresa emisora que usa el PDF de
   cotización: razón social, dirección, teléfono, email, términos y condiciones, cuentas
   bancarias. Idempotente (`INSERT ... ON CONFLICT (key) DO NOTHING`).

```bash
psql -h <host> -U <user> -d scaramutti_tms_dev -f db/baseline.sql
psql -h <host> -U <user> -d scaramutti_tms_dev -f db/seed_system_settings.sql
```

3. **Arrancar el backend** (`mvn quarkus:dev`): el `DevDataSeeder` siembra los **fixtures de
   desarrollo** (usuarios admin/lcampos/inactivo, monedas, términos de pago, 24 tipos de
   servicio). Solo corre en dev/test (`@UnlessBuildProfile("prod")`), nunca en prod.

> Los tests de integración corren contra esta misma BD de desarrollo, así que `system_settings`
> debe estar sembrado (paso 2) antes de correrlos. Si faltara, el PDF se genera igual pero sin
> datos de empresa (`PdfSettingsService` degrada a vacío, no crashea).

## Producción

Mismos scripts, aplicación manual como parte del runbook de despliegue:

- `baseline.sql` — una vez, al crear el schema.
- `seed_system_settings.sql` — una vez; idempotente, re-ejecutarlo no duplica ni pisa.

El `DevDataSeeder` **no** corre en prod. Los datos de `system_settings` son editables sin
redeploy con un `UPDATE` puntual sobre `cotizaciones.system_settings`.
