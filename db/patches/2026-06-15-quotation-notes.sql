-- Patch aditivo: observaciones de cotización (client_note / internal_note)
-- Feature: docs/prd/PRD-observaciones-cotizacion.md  (PR feature/quotation-notes)
-- Idempotente. Aplicar UNA vez a BDs YA existentes (dev local y prod) — en una BD nueva
-- el baseline.sql ya las trae. NO requiere backfill (RN-06: las cotizaciones viejas quedan NULL).
-- ⚠️ PROD: la DB se comparte con v1 → BACKUP ANTES de correr esto.

ALTER TABLE cotizaciones.quotations ADD COLUMN IF NOT EXISTS client_note   TEXT;
ALTER TABLE cotizaciones.quotations ADD COLUMN IF NOT EXISTS internal_note TEXT;

-- Postgres no soporta ADD CONSTRAINT IF NOT EXISTS → bloque idempotente.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_quotations_client_note_len') THEN
        ALTER TABLE cotizaciones.quotations
            ADD CONSTRAINT chk_quotations_client_note_len
            CHECK (client_note IS NULL OR char_length(client_note) <= 500);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_quotations_internal_note_len') THEN
        ALTER TABLE cotizaciones.quotations
            ADD CONSTRAINT chk_quotations_internal_note_len
            CHECK (internal_note IS NULL OR char_length(internal_note) <= 500);
    END IF;
END $$;

COMMENT ON COLUMN cotizaciones.quotations.client_note   IS 'Observación opcional visible para el cliente (PDF + UI). Texto libre, máx 500. Se renderiza escapada.';
COMMENT ON COLUMN cotizaciones.quotations.internal_note IS 'Observación opcional SOLO interna. NUNCA se renderiza en el PDF ni en salidas hacia el cliente (RN-03).';
