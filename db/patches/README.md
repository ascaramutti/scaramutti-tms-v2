# ⚠️ HISTÓRICO: NO APLICAR

Parches SQL aplicados **manualmente** a producción en la era pre-Flyway
(junio 2026). Todos están ya incorporados en el baseline de Flyway
(`backend/src/main/resources/db/migration/V001__baseline.sql`, foto de prod
al 2026-07-06): volver a ejecutar cualquiera de estos archivos sobre una DB
gestionada por Flyway es, en el mejor caso, un no-op y, en el peor, un error.

| Patch | Feature que introdujo |
|---|---|
| `2026-06-15-quotation-notes.sql` | Notas/observaciones de cotización (v2.0.0) |
| `2026-06-16-quotation-status.sql` | Estados de cotización (v2.1.0) |
| `2026-06-19-quotation-conditions.sql` | Condiciones seleccionables (v2.2.0) |
| `2026-06-19-remove-pdf-terms-setting.sql` | Limpieza del setting de términos del PDF (v2.2.0) |

**Todo cambio de schema desde F0 (2026-07-07) es una migración Flyway** nueva
(`V00X__descripcion.sql` en `backend/src/main/resources/db/migration/`), que
el backend aplica al arrancar y que staging ensaya contra la copia de prod
antes de llegar a `main`. Reglas de la cadena en el README de esa carpeta.

Esta carpeta se conserva solo como registro de qué se aplicó a mano y cuándo.
