-- V006__almacen_withdrawals_updated_at.sql
-- Agrega updated_at a almacen.withdrawals. El alta de retiros (V002) solo tenia
-- withdrawn_at, suficiente cuando el retiro nunca se editaba: su version (el ETag)
-- era withdrawn_at. La edicion y la anulacion necesitan una columna que bumpear en
-- cada cambio para el If-Match optimista (mismo patron que products/facturas), asi
-- que withdrawn_at deja de alcanzar como version.
--
-- Backfill: las filas existentes nunca se editaron, su version sigue siendo
-- withdrawn_at. Se agrega con DEFAULT CURRENT_TIMESTAMP (misma clase que withdrawn_at
-- y created_at del schema, para que un INSERT que la omita no falle), luego se rellenan
-- las filas existentes con withdrawn_at y recien se marca NOT NULL. En adelante el
-- @PrePersist inicializa updated_at = withdrawn_at al crear, y la edicion/anulacion lo
-- bumpea; el DEFAULT solo cubre los INSERT nativos que no la setean.

ALTER TABLE almacen.withdrawals
    ADD COLUMN updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP;

UPDATE almacen.withdrawals
    SET updated_at = withdrawn_at;

ALTER TABLE almacen.withdrawals
    ALTER COLUMN updated_at SET NOT NULL;
