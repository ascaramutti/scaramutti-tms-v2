-- Patch aditivo: estados de cotizacion (ciclo de vida + motivo de rechazo)
-- Feature: docs/prd/PRD-estados-cotizacion.md  (ADR-004..008)
-- Idempotente. Aplicar UNA vez a BDs YA existentes (dev local y prod) — en una BD nueva
-- el baseline.sql ya trae el CHECK ampliado + rejection_reason.
-- ⚠️ PROD: la DB se comparte con v1 → BACKUP ANTES de correr esto. Schema `cotizaciones` SOLO.
--
-- VERIFICACION PREVIA (manual, antes de correr el backfill del paso 4):
--   SELECT status, count(*) FROM cotizaciones.quotations GROUP BY status;
--   -> debe devolver solo DRAFT y/o SENT (supuesto del PRD; el CHECK viejo no permite otra cosa).
--
-- ORDEN IMPORTANTE: ampliar el CHECK (paso 3) ANTES del backfill (paso 4); el CHECK viejo
-- (IN ('DRAFT','SENT')) rechazaria el UPDATE a 'EXPIRED'.

-- 1. Motivo de rechazo (ADR-007). TEXT + CHECK de longitud (fuente de verdad del tope = CHECK).
--    Nullable: solo se llena cuando status = 'REJECTED'.
ALTER TABLE cotizaciones.quotations ADD COLUMN IF NOT EXISTS rejection_reason TEXT;

-- 2. CHECK de longitud del motivo (Postgres no soporta ADD CONSTRAINT IF NOT EXISTS → bloque idempotente).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_quotations_rejection_reason_len') THEN
        ALTER TABLE cotizaciones.quotations
            ADD CONSTRAINT chk_quotations_rejection_reason_len
            CHECK (rejection_reason IS NULL OR char_length(rejection_reason) <= 500);
    END IF;
END $$;

-- 3. Ampliar el CHECK de status: DRAFT/SENT -> los 5 estados (ADR-004).
--    El nuevo set es superconjunto del viejo → ninguna fila existente lo viola (todas DRAFT/SENT).
--    Se reemplaza (DROP + ADD) porque los valores permitidos cambian (no hay "alter check" en PG).
ALTER TABLE cotizaciones.quotations DROP CONSTRAINT IF EXISTS chk_quotations_status;
ALTER TABLE cotizaciones.quotations
    ADD CONSTRAINT chk_quotations_status
    CHECK (status IN ('DRAFT','SENT','ACCEPTED','REJECTED','EXPIRED'));

-- 4. Backfill (ADR-005): marcar EXPIRED las SENT cuya validez ya paso. Solo SENT vence (ADR-004:
--    los DRAFT no vencen). Idempotente: una 2da corrida no encuentra SENT vencidas.
--    Base de la validez = created_at + validity_days (NO sent_at; ADR-005).
UPDATE cotizaciones.quotations
   SET status = 'EXPIRED',
       updated_at = now()
 WHERE status = 'SENT'
   AND created_at + (validity_days || ' days')::interval < now();

COMMENT ON COLUMN cotizaciones.quotations.rejection_reason IS
    'Motivo del rechazo (texto libre, max 500). Solo presente si status=REJECTED. INTERNO — NUNCA en el PDF ni salidas hacia el cliente (ADR-007, hereda regla ADR-003).';
