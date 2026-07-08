-- R__almacen_stock_views.sql
-- VIEWs de stock y kardex del modulo Almacen (repeatable: Flyway las re-aplica
-- si cambia su contenido). Fuente de diseno: almacen/03_ESQUEMA_BD.md seccion 6.
-- El stock NUNCA se persiste -- siempre se calcula desde estas VIEWs.

-- Kardex unificado: apertura (+) union entradas activas (+) union retiros activos (-).
CREATE OR REPLACE VIEW almacen.stock_movements AS
  SELECT 'APERTURA'::text AS movement_type, ob.product_id, ob.quantity,
         ob.registered_at AS moved_at, ob.registered_by, NULL::integer AS source_id
    FROM almacen.opening_balances ob
  UNION ALL
  SELECT 'ENTRADA', pii.product_id, pii.quantity, pi.created_at, pi.registered_by, pi.id
    FROM almacen.purchase_invoice_items pii
    JOIN almacen.purchase_invoices pi ON pi.id = pii.invoice_id
   WHERE pi.status = 'ACTIVE'          -- entradas anuladas no mueven stock
  UNION ALL
  SELECT 'SALIDA', w.product_id, -w.quantity, w.withdrawn_at, w.registered_by, w.id
    FROM almacen.withdrawals w
   WHERE w.status = 'ACTIVE';

-- Stock actual por producto (nunca persistido -- siempre exacto).
CREATE OR REPLACE VIEW almacen.product_stock AS
  SELECT p.id AS product_id, COALESCE(SUM(m.quantity), 0) AS stock
    FROM almacen.products p
    LEFT JOIN almacen.stock_movements m ON m.product_id = p.id
   GROUP BY p.id;
