-- ============================================================================
-- Seed de system_settings para el PDF de cotizacion.
-- Datos de la empresa emisora + cuentas bancarias (editables sin redeploy).
-- Idempotente (ON CONFLICT DO NOTHING). Aplicar manualmente en dev y prod
-- (paso 2 del db/README); el DevDataSeeder NO lo siembra.
--
-- `quotation.pdf_bank_accounts`: array JSON de {bank, account, cci}.
-- `quotation.pdf_bank_accounts_intro`: cabecera (texto plano) de esa tabla — la cara visible
--   del marcador [[BANK_ACCOUNTS]] (RN-09). NO es una condicion del catalogo.
-- Las CONDICIONES GENERALES del PDF ya NO viven aca: salen del catalogo por
-- cotizacion (cotizaciones.conditions via la junction) — ver ADR-009 / US-006.
-- ============================================================================

INSERT INTO cotizaciones.system_settings (key, value, updated_at) VALUES
  ('company.legal_name', 'Transportes Scaramutti S.A.C.', NOW()),
  ('company.address', 'Cal. Jircan Mza. A Lote. 2 - Urb. Huertos de Naranjal - San Martín de Porres - Lima - Lima', NOW()),
  ('company.phone', 'Ofic.: 5927868 / Cel. Entel: 995066080', NOW()),
  ('company.email', 'ascaramutti@transportesscaramutti.pe', NOW()),
  ('quotation.pdf_bank_accounts_intro',
   'El cliente deberá realizar el pago de las facturas en cualquiera de las siguientes cuentas bancarias a nombre de Transportes Scaramutti S.A.C.:',
   NOW()),
  ('quotation.pdf_bank_accounts',
   '[{"bank":"BCP – Soles","account":"192-1737180-0-72","cci":"002-192-001737180072-37"},{"bank":"BCP – Dólares","account":"192-1704575-1-38","cci":"002-192-001704575138-39"},{"bank":"Scotiabank – Soles","account":"000-7340184","cci":"009-229-00007340184-66"},{"bank":"Scotiabank – Dólares","account":"000-3479304","cci":"009-229-000003479304-62"},{"bank":"BBVA – Soles","account":"011-01270100029511","cci":"011-12700010002951181"},{"bank":"BBVA – Dólares","account":"011-01270100029538","cci":"011-12700010002953884"}]',
   NOW())
ON CONFLICT (key) DO NOTHING;
