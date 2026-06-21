-- ============================================================================
-- US-006 — cleanup: el setting `quotation.pdf_terms` queda muerto.
--
-- El PDF de cotizacion ahora arma las CONDICIONES GENERALES desde el catalogo
-- por cotizacion (cotizaciones.conditions via la junction quotation_conditions),
-- no desde este setting fijo a nivel empresa. Se elimina la fila.
--
-- `quotation.pdf_bank_accounts` SE MANTIENE: sigue alimentando la tabla de
-- cuentas bancarias del PDF (el marcador [[BANK_ACCOUNTS]] lo agrega el codigo).
--
-- Idempotente: borrar una key inexistente no hace nada.
-- ============================================================================

DELETE FROM cotizaciones.system_settings WHERE key = 'quotation.pdf_terms';
