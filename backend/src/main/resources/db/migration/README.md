# Cadena de migraciones Flyway — reglas

Esta carpeta es la **única fuente de verdad del schema**. Flyway la aplica al arrancar el
backend (`quarkus.flyway.migrate-at-start=true`). `V001__baseline.sql` es la foto schema-only
de producción al 2026-07-06. Las DBs que ya tenían ese schema fueron baselineadas en su
momento (la V001 quedó marcada como aplicada sin ejecutarse) y después se retiró
`baseline-on-migrate` a propósito: una DB no vacía sin `flyway_schema_history` falla
ruidosamente en vez de adoptarse en silencio. Solo una DB vacía (dev local, tests, CI)
ejecuta la V001 completa.

## Reglas (contrato entre los dos proyectos paralelos — Almacén y Operaciones)

1. **Numeración única secuencial** (`V002`, `V003`, …). Dos PRs con el mismo número → el
   segundo **renumera al mergear** (el conflicto se ve en el PR, nunca en la DB).
2. **Prefijo de módulo en la descripción**: `V00X__operaciones_*`, `V00X__almacen_*`,
   `V00X__public_*`.
3. **Una migración aplicada NUNCA se edita** (Flyway valida checksums). Corrección = nueva
   migración.
4. **`public` = zona compartida con v1**: solo cambios **aditivos-compatibles** (ampliar
   columna, agregar columna nullable) y **coordinados** (se anuncian en el TABLERO).
   Renombrar/borrar en `public`: prohibido mientras v1 viva.
5. **VIEWs y funciones** → `R__` (repeatable): se re-aplican cuando cambia su contenido, no
   se versionan.
6. `out-of-order=false` (default): el orden de aplicación es ley.
7. Sin rollback automático (Flyway community): revertir = **migración compensatoria** nueva
   + PITR/backup como red de seguridad.

## Datos, no schema

Los seeds de **datos de negocio** viven fuera de la cadena: `db/seed_system_settings.sql` (datos
reales de la empresa, manual e idempotente) y `DevDataSeeder` (fixtures de dev/test, nunca en prod).

Excepción: los **catálogos fundacionales del módulo** (roles nuevos, listas cerradas que el
propio módulo necesita para funcionar desde el día 1 — ej. `almacen.units_of_measure`)
**sí viajan dentro de su migración** vía `INSERT` literal (ver la migración del schema
`almacen`). Un dominio cerrado sin CRUD ni atributos propios no es un catálogo: va como
columna + `CHECK` y enum en el código (ej. `operaciones.services.trip_scope`). Motivo: son parte del contrato del módulo, no datos operativos que
cambien por fuera de una migración coordinada — y así llegan solos a todo entorno (dev/staging/prod)
sin depender de un paso manual (regla del programa: cambios de DB = solo Flyway).
