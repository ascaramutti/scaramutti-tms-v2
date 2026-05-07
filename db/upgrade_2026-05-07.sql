-- =============================================================================
-- Upgrade del schema cotizaciones — 2026-05-07
-- =============================================================================
-- Aplica los cambios derivados de la revisión de contrato API:
--   - quotations: agregar quotation_type, status, updated_by
--   - quotation_items: agregar parent_item_id, insured_amount, internal_reference_price
--   - constraints e índices nuevos
--
-- Pre-requisitos:
--   - El baseline previo ya está aplicado en prod
--   - Las tablas de cotizaciones están vacías (sin data productiva todavía)
--
-- Si las tablas tuvieran data, este script seguiría siendo seguro porque las
-- columnas nuevas tienen DEFAULT o son nullable.
-- =============================================================================

BEGIN;

-- 1. quotations: nuevas columnas + constraints

ALTER TABLE cotizaciones.quotations
    ADD COLUMN IF NOT EXISTS quotation_type VARCHAR(20) NOT NULL DEFAULT 'TRANSPORTE',
    ADD COLUMN IF NOT EXISTS status         VARCHAR(20) NOT NULL DEFAULT 'DRAFT';

-- updated_by: agregar como nullable, backfill desde created_by, después NOT NULL
ALTER TABLE cotizaciones.quotations
    ADD COLUMN IF NOT EXISTS updated_by INTEGER REFERENCES public.users(id);

UPDATE cotizaciones.quotations SET updated_by = created_by WHERE updated_by IS NULL;

ALTER TABLE cotizaciones.quotations
    ALTER COLUMN updated_by SET NOT NULL;

-- Constraints (idempotentes con DO blocks porque ALTER TABLE ADD CONSTRAINT no soporta IF NOT EXISTS)

DO $$ BEGIN
    ALTER TABLE cotizaciones.quotations
        ADD CONSTRAINT chk_quotations_type CHECK (quotation_type IN ('TRANSPORTE','ALQUILER'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE cotizaciones.quotations
        ADD CONSTRAINT chk_quotations_status CHECK (status IN ('DRAFT','SENT'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE cotizaciones.quotations
        ADD CONSTRAINT chk_quotations_validity_days CHECK (validity_days > 0 AND validity_days <= 365);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Índices nuevos
CREATE INDEX IF NOT EXISTS idx_quotations_status     ON cotizaciones.quotations(status);
CREATE INDEX IF NOT EXISTS idx_quotations_type       ON cotizaciones.quotations(quotation_type);
CREATE INDEX IF NOT EXISTS idx_quotations_created_by ON cotizaciones.quotations(created_by);

-- Comentarios
COMMENT ON COLUMN cotizaciones.quotations.quotation_type IS 'TRANSPORTE (con origen/destino) | ALQUILER (sin ruta, por días/unidades)';
COMMENT ON COLUMN cotizaciones.quotations.status        IS 'DRAFT (borrador interno) | SENT (enviada al cliente). Estado vencido se calcula desde created_at + validity_days';

-- 2. quotation_items: nuevas columnas + constraints

ALTER TABLE cotizaciones.quotation_items
    ADD COLUMN IF NOT EXISTS parent_item_id           BIGINT REFERENCES cotizaciones.quotation_items(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS insured_amount           NUMERIC(14,2),
    ADD COLUMN IF NOT EXISTS internal_reference_price NUMERIC(12,2);

DO $$ BEGIN
    ALTER TABLE cotizaciones.quotation_items
        ADD CONSTRAINT chk_items_quantity CHECK (quantity > 0);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE cotizaciones.quotation_items
        ADD CONSTRAINT chk_items_unit_price CHECK (unit_price >= 0);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE cotizaciones.quotation_items
        ADD CONSTRAINT chk_items_igv CHECK (igv_percentage >= 0 AND igv_percentage <= 100);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE cotizaciones.quotation_items
        ADD CONSTRAINT chk_items_no_self_parent CHECK (parent_item_id IS NULL OR parent_item_id <> id);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE INDEX IF NOT EXISTS idx_quotation_items_parent ON cotizaciones.quotation_items(parent_item_id);

COMMENT ON COLUMN cotizaciones.quotation_items.parent_item_id IS
    'Self-reference para Servicio Integral: ítems hijos referencian al ítem padre que tiene el precio total. NULL para ítems independientes.';
COMMENT ON COLUMN cotizaciones.quotation_items.insured_amount IS
    'Valor asegurado de la carga (solo aplica a ítems de tipo Seguro de Carga)';
COMMENT ON COLUMN cotizaciones.quotation_items.internal_reference_price IS
    'Precio interno de referencia para ítems hijos del Servicio Integral. NO se muestra en el PDF al cliente.';

COMMIT;

-- =============================================================================
-- Verificación post-upgrade (opcional, ejecutar manualmente):
--
-- SELECT column_name, data_type FROM information_schema.columns
--  WHERE table_schema='cotizaciones' AND table_name='quotations'
--  ORDER BY ordinal_position;
--
-- SELECT conname FROM pg_constraint
--  WHERE connamespace = 'cotizaciones'::regnamespace
--  ORDER BY conname;
-- =============================================================================
