-- V005__almacen_ci_index_hardening.sql
-- Cierra los dos gaps de conformidad DDL-vs-contrato que quedaron de la
-- auditoria del schema almacen (2026-07-08). Son los ultimos de la misma
-- clase que ya cerraron V003 (product_categories.name) y V004 (suppliers
-- name/ruc): el DDL congelado (03_ESQUEMA_BD.md) uso UNIQUE/indices planos
-- donde el contrato (RN-WH10 / RN-WH14) pide case-insensitive o trgm.
--
-- Con esta migracion el schema almacen queda conforme al contrato: lo que
-- sigue del modulo solo agrega endpoints sobre tablas ya existentes.

-- 1. SKU (products.code) unico case-insensitive (RN-WH10 lo nombra explicito).
--    El UNIQUE plano de V002 es case-sensitive: 'ABC-1' y 'abc-1' coexistirian.
--    code es nullable (SKU opcional, autogenerable) -> indice parcial; los NULL
--    quedan fuera y no compiten entre si.
CREATE UNIQUE INDEX uq_products_code_ci
    ON almacen.products (lower(code))
    WHERE code IS NOT NULL;

-- 2. Busqueda trgm sobre suppliers.ruc (RN-WH14). El listado de proveedores ya
--    filtra por ruc con ILIKE tokenizado, pero V002 solo trae el GIN de name
--    (idx_suppliers_name_trgm). Cierra la asimetria name-si/ruc-no. Impacto de
--    performance menor a escala realista (ruc = 11 digitos), pero deja la
--    conformidad completa con el contrato.
CREATE INDEX idx_suppliers_ruc_trgm
    ON almacen.suppliers USING GIN (ruc public.gin_trgm_ops);
