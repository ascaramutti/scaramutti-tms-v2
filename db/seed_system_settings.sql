-- ============================================================================
-- Seed de system_settings para el PDF de cotizacion.
-- Datos de la empresa emisora + T&C + cuentas bancarias (editables sin redeploy).
-- Idempotente (ON CONFLICT DO NOTHING). Aplicar manualmente en dev y prod
-- (paso 2 del db/README); el DevDataSeeder NO lo siembra.
--
-- `quotation.pdf_terms`: array JSON de bullets de T&C. El marcador "[[BANK_ACCOUNTS]]"
--   indica al template del PDF donde insertar la tabla de cuentas bancarias.
-- `quotation.pdf_bank_accounts`: array JSON de {bank, account, cci}.
-- ============================================================================

INSERT INTO cotizaciones.system_settings (key, value, updated_at) VALUES
  ('company.legal_name', 'Transportes Scaramutti S.A.C.', NOW()),
  ('company.address', 'Cal. Jircan Mza. A Lote. 2 - Urb. Huertos de Naranjal - San Martín de Porres - Lima - Lima', NOW()),
  ('company.phone', 'Ofic.: 5927868 / Cel. Entel: 995066080', NOW()),
  ('company.email', 'ascaramutti@transportesscaramutti.pe', NOW()),
  ('quotation.pdf_terms',
   '["El costo de Stand By comenzará a contabilizarse cuando, en un acumulado de más de seis (6) horas, las unidades se encuentren paralizadas a disposición del cliente para cargar o descargar, estén o no en el punto de carga o descarga.","En caso el cliente cuente con seguro de carga, deberá incluir como beneficiario a Transportes Scaramutti S.A.C. Asimismo, dicho seguro debe cubrir el transporte, la carga y la descarga.","En caso se solicite seguro de carga, el valor de este se sumará al precio del servicio de transporte o podrá cotizarse por separado. Para este supuesto, se requerirá el valor aproximado de la carga.","El cliente deberá realizar los trámites administrativos y demás gestiones necesarias para el ingreso de la unidad al punto de carga y/o descarga, así como del personal operativo y de mantenimiento.","En caso el cliente desee cancelar en una moneda diferente a la pactada en la cotización, el tipo de cambio aplicable será el vigente en la fecha de cancelación de la factura.","La presente cotización se dará por aceptada una vez recibida la confirmación mediante orden de servicio, orden de compra, correo electrónico o cualquier otro medio de comunicación verificable. De esta manera, se entenderá que el cliente acepta todas las tarifas y condiciones aquí establecidas.","El cliente deberá realizar el pago de las facturas en cualquiera de las siguientes cuentas bancarias a nombre de Transportes Scaramutti S.A.C.:","[[BANK_ACCOUNTS]]","Asimismo, recordamos que nuestra empresa cuenta con certificación SGS; nuestras unidades son monitoreadas por GPS y nuestros conductores están altamente capacitados, además de contar con seguro SCTR, de pensión y de salud.","Por último, nuestros conductores y unidades cuentan con pase a los terminales portuarios APM, DP World y Chancay."]',
   NOW()),
  ('quotation.pdf_bank_accounts',
   '[{"bank":"BCP – Soles","account":"192-1737180-0-72","cci":"002-192-001737180072-37"},{"bank":"BCP – Dólares","account":"192-1704575-1-38","cci":"002-192-001704575138-39"},{"bank":"Scotiabank – Soles","account":"000-7340184","cci":"009-229-00007340184-66"},{"bank":"Scotiabank – Dólares","account":"000-3479304","cci":"009-229-000003479304-62"},{"bank":"BBVA – Soles","account":"011-01270100029511","cci":"011-12700010002951181"},{"bank":"BBVA – Dólares","account":"011-01270100029538","cci":"011-12700010002953884"}]',
   NOW())
ON CONFLICT (key) DO NOTHING;
