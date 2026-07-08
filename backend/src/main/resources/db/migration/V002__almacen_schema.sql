-- V002__almacen_schema.sql
-- Schema del modulo Almacen (control de inventario de repuestos/insumos de la flota).
-- Fuente de diseno (verdad): almacen/03_ESQUEMA_BD.md rev.3 (+ Delta-2 unicidad compuesta),
-- almacen/10_CONTRATO_ALMACEN.md. Copiado literal, no improvisar sobre este DDL.

-- =============================================================================
-- 0. Flota: subtipo nuevo en public (aditivo -- v1 no se toca)
-- =============================================================================
-- ESCOLTAS: vehiculos propios de resguardo. 3er subtipo de la flota. El supertipo
-- real (fleet_units, generalizacion disyunta y total) queda para la fase de
-- flota/RRHH (post-v1); por ahora tabla hermana de tractors/trailers, misma
-- forma y estandares.
CREATE TABLE public.escort_vehicles (
    id         SERIAL PRIMARY KEY,
    plate      VARCHAR(6) NOT NULL UNIQUE,  -- estandar del dueno: 6 chars sin guion (presentacion lo agrega)
    brand      VARCHAR(50),
    model      VARCHAR(50),
    year       INTEGER,
    status_id  INTEGER NOT NULL REFERENCES public.resource_statuses(id),
    is_active  BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =============================================================================
-- 1. Catalogos del modulo
-- =============================================================================
CREATE SCHEMA IF NOT EXISTS almacen;
COMMENT ON SCHEMA almacen IS 'Control de inventario del almacen (repuestos/insumos para la flota)';

CREATE TABLE almacen.product_categories (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE almacen.units_of_measure (
    id         SERIAL PRIMARY KEY,
    code       VARCHAR(10) NOT NULL UNIQUE,   -- UND, LT, GAL, KG, MT, CJA, JGO
    name       VARCHAR(50) NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE almacen.suppliers (
    id           SERIAL PRIMARY KEY,
    name         VARCHAR(200) NOT NULL UNIQUE,
    ruc          VARCHAR(11) UNIQUE,
    phone        VARCHAR(9),          -- estandar del dueno: canal peruano de 9 digitos (como clients)
    contact_name VARCHAR(100),
    is_active    BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_suppliers_name_trgm ON almacen.suppliers USING GIN (name public.gin_trgm_ops);

-- =============================================================================
-- 2. Producto
-- =============================================================================
CREATE TABLE almacen.products (
    id                  SERIAL PRIMARY KEY,
    code                VARCHAR(30) UNIQUE,            -- SKU opcional; autogenerable (PRO-0001)
    name                VARCHAR(200) NOT NULL,         -- Delta-2: unico COMPUESTO con marca+parte (indice abajo)
    category_id         INTEGER NOT NULL REFERENCES almacen.product_categories(id),
    unit_of_measure_id  INTEGER NOT NULL REFERENCES almacen.units_of_measure(id),
    brand               VARCHAR(100),
    part_number         VARCHAR(100),
    attributes          JSONB NOT NULL DEFAULT '{}'::jsonb, -- caracteristicas flexibles clave-valor
    min_stock           NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (min_stock >= 0), -- umbral "Bajo"
    observations        TEXT,
    is_active           BOOLEAN NOT NULL DEFAULT true,
    created_by          INTEGER NOT NULL REFERENCES public.users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Delta-2 (aprobado por el dueno 2026-07-04): la identidad de negocio es
-- (nombre, marca, n. de parte) -- mismo nombre y marca pueden repetirse con
-- distinto n. de parte (variantes/modelos). COALESCE porque marca/parte son
-- opcionales: sin el, PostgreSQL trata cada NULL como distinto y los
-- genericos (sin marca/parte) se duplicarian. lower() = unicidad
-- case-insensitive. El backend normaliza: trim + cadena vacia -> NULL.
CREATE UNIQUE INDEX uq_products_identity ON almacen.products (
    lower(name),
    lower(COALESCE(brand, '')),
    lower(COALESCE(part_number, ''))
);
CREATE INDEX idx_products_name_trgm ON almacen.products USING GIN (name public.gin_trgm_ops);
CREATE INDEX idx_products_category  ON almacen.products(category_id);

-- =============================================================================
-- 3. Corte inicial de inventario (decision del dueno)
-- =============================================================================
-- "Con cuanto inicia el sistema": UNA apertura por producto, registrada al
-- arrancar (o al incorporar un producto que ya existia fisicamente). Es el
-- primer movimiento del kardex. Inmutable (una correccion = anular retiros o
-- registrar entrada; no se edita la apertura).
CREATE TABLE almacen.opening_balances (
    id            SERIAL PRIMARY KEY,
    product_id    INTEGER NOT NULL UNIQUE REFERENCES almacen.products(id),
    quantity      NUMERIC(12,2) NOT NULL CHECK (quantity >= 0),
    observations  TEXT,
    registered_by INTEGER NOT NULL REFERENCES public.users(id),
    registered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =============================================================================
-- 4. Entradas (factura + items)
-- =============================================================================
CREATE TABLE almacen.purchase_invoices (
    id              SERIAL PRIMARY KEY,
    supplier_id     INTEGER NOT NULL REFERENCES almacen.suppliers(id),  -- NOT NULL (una compra siempre tiene proveedor)
    invoice_number  VARCHAR(50) NOT NULL,
    invoice_date    DATE NOT NULL,
    guide_number    VARCHAR(50),                       -- guia de remision (1 factura -> 1 guia, dato de finanzas)
    currency_id     INTEGER NOT NULL REFERENCES public.currencies(id),
    -- SIN columna total: se calcula de los items (patron v2: los totales no se persisten)
    attachment_path VARCHAR(255),                      -- foto/PDF (fase 1.5)
    observations    TEXT,
    registered_by   INTEGER NOT NULL REFERENCES public.users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- @PreUpdate (entradas editables)
    -- ANULACION de entradas, espejo de withdrawals ("la factura se registro por
    -- error"). "Eliminar" en la UI = esto; nunca DELETE.
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    cancel_reason   TEXT,
    cancelled_by    INTEGER REFERENCES public.users(id),
    cancelled_at    TIMESTAMPTZ,
    CONSTRAINT chk_invoices_status CHECK (status IN ('ACTIVE','CANCELLED')),
    CONSTRAINT chk_invoices_cancel_consistent CHECK (
        (status = 'ACTIVE'    AND cancel_reason IS NULL AND cancelled_by IS NULL AND cancelled_at IS NULL) OR
        (status = 'CANCELLED' AND cancel_reason IS NOT NULL AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL)
    )
);
CREATE INDEX idx_purchase_invoices_supplier ON almacen.purchase_invoices(supplier_id);
CREATE INDEX idx_purchase_invoices_date     ON almacen.purchase_invoices(invoice_date);
-- UNIQUE PARCIAL: la unicidad proveedor+numero solo aplica a facturas ACTIVAS
-- -- si una se anulo por error de registro, la corregida puede re-usar el
-- mismo numero.
CREATE UNIQUE INDEX uq_purchase_invoices_active
    ON almacen.purchase_invoices(supplier_id, invoice_number)
    WHERE status = 'ACTIVE';

CREATE TABLE almacen.purchase_invoice_items (
    id           SERIAL PRIMARY KEY,
    invoice_id   INTEGER NOT NULL REFERENCES almacen.purchase_invoices(id) ON DELETE CASCADE,
    product_id   INTEGER NOT NULL REFERENCES almacen.products(id),
    quantity     NUMERIC(12,2) NOT NULL CHECK (quantity > 0),
    unit_price   NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_invoice_items_invoice ON almacen.purchase_invoice_items(invoice_id);
CREATE INDEX idx_invoice_items_product ON almacen.purchase_invoice_items(product_id);

-- =============================================================================
-- 5. Salidas (retiros) -- con ANULACION y unidad disyunta
-- =============================================================================
CREATE TABLE almacen.withdrawals (
    id                INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id        INTEGER NOT NULL REFERENCES almacen.products(id),
    quantity          NUMERIC(12,2) NOT NULL CHECK (quantity > 0),
    withdrawn_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    received_by       INTEGER NOT NULL REFERENCES public.workers(id),
    -- Unidad destino: subtipos DISYUNTOS de la flota (a lo sumo UNA).
    tractor_id        INTEGER REFERENCES public.tractors(id),
    trailer_id        INTEGER REFERENCES public.trailers(id),
    escort_vehicle_id INTEGER REFERENCES public.escort_vehicles(id),
    observations      TEXT,
    registered_by     INTEGER NOT NULL REFERENCES public.users(id),
    -- Estado como columna + CHECK (patron ratificado, QuotationStatus/operaciones).
    -- Los retiros NO se borran ni se "ajustan" -- se ANULAN con motivo (caso
    -- real: el camion no necesitaba el repuesto) y el stock vuelve solo (las
    -- VIEWs excluyen anulados).
    status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    cancel_reason     TEXT,
    cancelled_by      INTEGER REFERENCES public.users(id),
    cancelled_at      TIMESTAMPTZ,
    CONSTRAINT chk_withdrawals_status CHECK (status IN ('ACTIVE','CANCELLED')),
    CONSTRAINT chk_withdrawals_one_unit CHECK (
        (tractor_id IS NOT NULL)::int + (trailer_id IS NOT NULL)::int + (escort_vehicle_id IS NOT NULL)::int <= 1
    ),
    CONSTRAINT chk_withdrawals_cancel_consistent CHECK (
        (status = 'ACTIVE'    AND cancel_reason IS NULL AND cancelled_by IS NULL AND cancelled_at IS NULL) OR
        (status = 'CANCELLED' AND cancel_reason IS NOT NULL AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL)
    )
);
CREATE INDEX idx_withdrawals_product  ON almacen.withdrawals(product_id);
CREATE INDEX idx_withdrawals_date     ON almacen.withdrawals(withdrawn_at);
CREATE INDEX idx_withdrawals_tractor  ON almacen.withdrawals(tractor_id);
CREATE INDEX idx_withdrawals_trailer  ON almacen.withdrawals(trailer_id);
CREATE INDEX idx_withdrawals_escort   ON almacen.withdrawals(escort_vehicle_id);

-- =============================================================================
-- 5.2 Auditoria del modulo
-- =============================================================================
-- Una sola tabla cubre ediciones y anulaciones de entradas y retiros (y deja
-- espacio para productos/apertura si hiciera falta). Mismo molde que
-- operaciones.service_audit_logs. Se escribe desde el dia 1; UI de lectura
-- opcional (el detalle muestra el ultimo cambio; el resto queda consultable).
CREATE TABLE almacen.audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(30) NOT NULL,   -- PURCHASE_INVOICE | WITHDRAWAL | PRODUCT | OPENING_BALANCE
    entity_id   INTEGER NOT NULL,
    change_type VARCHAR(30) NOT NULL,   -- CREATED | FIELD_EDIT | CANCELLED
    field_name  VARCHAR(50),
    field_label VARCHAR(100),
    old_value   TEXT,
    new_value   TEXT,
    reason      TEXT NOT NULL,          -- justificacion (>=10) o motivo de anulacion
    changed_by  INTEGER NOT NULL REFERENCES public.users(id),
    logged_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_audit_entity CHECK (entity_type IN
        ('PURCHASE_INVOICE','WITHDRAWAL','PRODUCT','OPENING_BALANCE')),
    CONSTRAINT chk_audit_change CHECK (change_type IN ('CREATED','FIELD_EDIT','CANCELLED'))
);
CREATE INDEX idx_almacen_audit_entity ON almacen.audit_logs(entity_type, entity_id);

-- =============================================================================
-- 6. Roles nuevos (rev.3, almacen/03 seccion 8 -- aditivo a public.roles)
-- =============================================================================
-- finance_manager (jefa de finanzas): acceso SOLO a Almacen.
-- warehouse_keeper (encargado de almacen, eventual): idem.
-- Poder total dentro del modulo para ambos (registrar/editar/anular).
INSERT INTO public.roles (name, description) VALUES
    ('finance_manager',  'Jefa de Finanzas'),
    ('warehouse_keeper', 'Encargado de Almacén');

-- =============================================================================
-- 7. Seeds de catalogos iniciales (almacen/08_BRIEF_DISENO_ALMACEN.md,
--    almacen/09_PROMPT_CONSOLIDADO.md -- 7 categorias, 7 unidades)
-- =============================================================================
INSERT INTO almacen.product_categories (name) VALUES
    ('Repuestos'),
    ('Lubricantes'),
    ('Neumáticos'),
    ('Filtros'),
    ('EPP'),
    ('Herramientas'),
    ('Consumibles');

-- units_of_measure es lista CERRADA (RN-WH9): solo GET en la API; agregar una
-- unidad = nueva migracion coordinada.
INSERT INTO almacen.units_of_measure (code, name) VALUES
    ('UND', 'Unidad'),
    ('LT',  'Litro'),
    ('GAL', 'Galón'),
    ('KG',  'Kilogramo'),
    ('MT',  'Metro'),
    ('CJA', 'Caja'),
    ('JGO', 'Juego');
