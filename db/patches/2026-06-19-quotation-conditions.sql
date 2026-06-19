-- Patch aditivo: condiciones seleccionables por cotizacion (catalogo + junction)
-- Feature: docs/prd/PRD-condiciones-cotizacion.md  (ADR-009..011)
-- Idempotente. Aplicar UNA vez a BDs YA existentes (dev local y prod) — en una BD nueva
-- el baseline.sql ya trae las dos tablas (la semilla del catalogo va por el seed manual del modulo).
-- ⚠️ PROD: la DB se comparte con v1 → BACKUP ANTES de correr esto. Schema `cotizaciones` SOLO.
--
-- Que hace, en orden:
--   1. Catalogo cotizaciones.conditions (versionado: "editar" = is_active=false + INSERT nueva).
--   2. Junction cotizaciones.quotation_conditions (FK-only, PK compuesta = snapshot por referencia).
--   3. Semilla del catalogo desde system_settings['quotation.pdf_terms'] EXCLUYENDO "[[BANK_ACCOUNTS]]".
--   4. Backfill: linkear cada cotizacion existente a TODAS las condiciones sembradas (PDF identico).
--
-- VERIFICACION POST-MIGRACION (manual, GATE de deploy):
--   -- semilla: debe dar (nº de strings del array de prod) − 1, y el marcador NO debe ser fila:
--   SELECT count(*) FROM cotizaciones.conditions WHERE is_active;
--   SELECT NOT EXISTS (SELECT 1 FROM cotizaciones.conditions WHERE text = '[[BANK_ACCOUNTS]]') AS marker_excluido;
--   -- backfill: debe ser igual a count(quotations) × count(condiciones activas):
--   SELECT (SELECT count(*) FROM cotizaciones.quotation_conditions) AS links,
--          (SELECT count(*) FROM cotizaciones.quotations) * (SELECT count(*) FROM cotizaciones.conditions WHERE is_active) AS esperado;

-- 1. Catalogo de condiciones (ADR-009). display_order es nuevo en el proyecto (no esta en baseline previo).
--    Inmutable por versionado: el `text` NUNCA se UPDATE-a (ADR-009 §2); el snapshot historico vive aca.
CREATE TABLE IF NOT EXISTS cotizaciones.conditions (
    id            SERIAL PRIMARY KEY,
    text          TEXT NOT NULL,
    display_order INTEGER NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE cotizaciones.conditions IS
    'Catalogo versionado de condiciones generales del PDF de cotizacion (ADR-009). Fuente de verdad de las clausulas (reemplaza system_settings[''quotation.pdf_terms''] para ese fin). Inmutable por versionado: "editar" = is_active=false + INSERT nueva que hereda display_order; el `text` NUNCA se UPDATE-a (preserva el snapshot de cotizaciones ya emitidas). El marcador [[BANK_ACCOUNTS]] NO es fila de este catalogo (RN-09).';
COMMENT ON COLUMN cotizaciones.conditions.text IS
    'Texto de la clausula (cara al cliente, sale en el PDF). Inmutable: no se UPDATE-a; para cambiarlo se desactiva y se inserta una version nueva (ADR-009 §2).';
COMMENT ON COLUMN cotizaciones.conditions.display_order IS
    'Orden de impresion en el PDF (ASC). Una version nueva hereda el display_order de la que reemplaza. Reorder manual = diferido (DA-01).';
COMMENT ON COLUMN cotizaciones.conditions.is_active IS
    'Lectura (detalle/PDF) muestra activas O inactivas (snapshot, RN-05). Escritura (crear/editar) exige TODAS activas (Opcion B, RN-06 → QUO-007). El catalogo del wizard lista solo activas (RN-07).';

CREATE INDEX IF NOT EXISTS idx_conditions_display_order ON cotizaciones.conditions(display_order);

-- 2. Junction FK-only (ADR-009 §3). PK compuesta = unicidad (no se linkea 2x la misma condicion).
--    La fila inmutable del catalogo ES el snapshot → SIN columna de texto aca.
--    CASCADE en quotation_id (dependiente de la cotizacion, como quotation_items); NO en condition_id
--    (las condiciones no se borran, solo se desactivan — un borrado en cascada romperia el snapshot).
CREATE TABLE IF NOT EXISTS cotizaciones.quotation_conditions (
    quotation_id BIGINT  NOT NULL REFERENCES cotizaciones.quotations(id) ON DELETE CASCADE,
    condition_id INTEGER NOT NULL REFERENCES cotizaciones.conditions(id),
    PRIMARY KEY (quotation_id, condition_id)
);

COMMENT ON TABLE cotizaciones.quotation_conditions IS
    'Tabla de apoyo FK-only (ADR-009 §3): que condiciones del catalogo aplican a cada cotizacion. PK compuesta (quotation_id, condition_id) garantiza unicidad. SIN texto: la fila inmutable de cotizaciones.conditions ES el snapshot. El PDF/detalle resuelve por JOIN ordenando por display_order ASC (RN-04), mostrando activas O inactivas (RN-05).';

-- La PK (quotation_id, condition_id) ya indexa el acceso por quotation_id (prefijo). Indice extra
-- para los JOIN/borrados por condicion (ej. integridad referencial al desactivar, futuros reportes).
CREATE INDEX IF NOT EXISTS idx_quotation_conditions_condition ON cotizaciones.quotation_conditions(condition_id);

-- 3. Semilla del catalogo (RN-10a) — parse-and-insert dinamico desde el setting REAL de la BD.
--    El texto sale de system_settings (no se recopia en el patch) → cero drift con la fuente actual.
--    Excluye "[[BANK_ACCOUNTS]]" (RN-09). display_order 1..N contiguo (row_number sobre lo ya filtrado,
--    no la ordinalidad cruda — asi no queda hueco donde estaba el marcador). Idempotente: solo siembra
--    si el catalogo esta vacio.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM cotizaciones.conditions) THEN
        INSERT INTO cotizaciones.conditions (text, display_order, is_active)
        SELECT term, row_number() OVER (ORDER BY ord) AS display_order, true
        FROM (
            SELECT elem.value AS term, elem.ordinality AS ord
            FROM cotizaciones.system_settings s,
                 LATERAL jsonb_array_elements_text(s.value::jsonb) WITH ORDINALITY AS elem(value, ordinality)
            WHERE s.key = 'quotation.pdf_terms'
              AND elem.value <> '[[BANK_ACCOUNTS]]'   -- RN-09: el marcador no es una condicion
        ) filtered;
    END IF;
END $$;

-- 4. Backfill (RN-10b) — linkear cada cotizacion existente a TODAS las condiciones activas sembradas.
--    Hoy todas reciben todas → el producto cartesiano reproduce el estado actual ⇒ PDFs identicos.
--    Idempotente: PK compuesta + ON CONFLICT DO NOTHING (2da corrida no duplica — RN-03).
INSERT INTO cotizaciones.quotation_conditions (quotation_id, condition_id)
SELECT q.id, c.id
FROM cotizaciones.quotations q
CROSS JOIN cotizaciones.conditions c
WHERE c.is_active
ON CONFLICT (quotation_id, condition_id) DO NOTHING;
