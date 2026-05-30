-- =============================================================================
-- Scaramutti TMS — Base de Datos
-- =============================================================================
-- Versión: 1.0
-- Fecha: 2026-05-06
-- Autor: Angel Scaramutti
--
-- Genera la base de datos completa desde cero con dos schemas:
--   1) public         — operación de servicios de transporte
--   2) cotizaciones   — emisión y administración de cotizaciones comerciales
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Extensiones
-- -----------------------------------------------------------------------------

CREATE EXTENSION IF NOT EXISTS pg_trgm;
COMMENT ON EXTENSION pg_trgm IS 'Búsqueda fuzzy/aproximada para listados';


-- =============================================================================
-- 2. Schema PUBLIC — Operación de servicios de transporte
-- =============================================================================

-- 2.1 Catálogos --------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.cargo_types (
    id              SERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL UNIQUE,
    description     TEXT,
    standard_weight NUMERIC(10,2) NOT NULL,
    standard_length NUMERIC(10,2),
    standard_width  NUMERIC(10,2),
    standard_height NUMERIC(10,2),
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- GIN trgm index para busqueda fuzzy en listCargoTypes.
-- Acelera tanto ILIKE '%pat%' como similarity(name, q) usados en el endpoint.
-- Solo en name (no hay equivalente al ruc de clients).
CREATE INDEX IF NOT EXISTS idx_cargo_types_name_trgm ON public.cargo_types USING GIN (name gin_trgm_ops);

CREATE TABLE IF NOT EXISTS public.clients (
    id           SERIAL PRIMARY KEY,
    name         VARCHAR(200) NOT NULL UNIQUE,
    ruc          VARCHAR(11) NOT NULL UNIQUE,
    phone        VARCHAR(9),
    contact_name VARCHAR(100),
    is_active    BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- GIN trgm indexes para busqueda fuzzy del endpoint listClients.
-- gin_trgm_ops habilita los operadores `%` (similarity threshold) y `<->`
-- (distancia) sobre name y ruc. Sin estos el matching similarity full-scanea.
-- pg_trgm.similarity_threshold se mantiene en su default 0.3 (ajustable a
-- nivel cluster sin redeploy).
CREATE INDEX IF NOT EXISTS idx_clients_name_trgm ON public.clients USING GIN (name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_clients_ruc_trgm  ON public.clients USING GIN (ruc  gin_trgm_ops);

CREATE TABLE IF NOT EXISTS public.currencies (
    id        SERIAL PRIMARY KEY,
    code      CHAR(3) NOT NULL UNIQUE,
    symbol    VARCHAR(5) NOT NULL,
    name      VARCHAR(50),
    is_active BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS public.document_types (
    id                 SERIAL PRIMARY KEY,
    code               VARCHAR(10) NOT NULL UNIQUE,
    name               VARCHAR(50) NOT NULL,
    description        TEXT,
    max_length         INTEGER NOT NULL,
    validation_pattern VARCHAR(255),
    is_active          BOOLEAN NOT NULL DEFAULT true,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.resource_statuses (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS public.roles (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS public.service_statuses (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS public.service_types (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT true
);

-- 2.2 Recursos físicos -------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.tractors (
    id         SERIAL PRIMARY KEY,
    plate      VARCHAR(6) NOT NULL UNIQUE,
    brand      VARCHAR(50),
    model      VARCHAR(50),
    year       INTEGER,
    status_id  INTEGER NOT NULL REFERENCES public.resource_statuses(id),
    is_active  BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.trailers (
    id         SERIAL PRIMARY KEY,
    plate      VARCHAR(6) NOT NULL UNIQUE,
    type       VARCHAR(50) NOT NULL,
    status_id  INTEGER NOT NULL REFERENCES public.resource_statuses(id),
    is_active  BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.workers (
    id               SERIAL PRIMARY KEY,
    first_name       VARCHAR(100) NOT NULL,
    last_name        VARCHAR(100) NOT NULL,
    document_type_id INTEGER NOT NULL REFERENCES public.document_types(id),
    document_number  VARCHAR(20) NOT NULL UNIQUE,
    phone            VARCHAR(9),
    "position"       VARCHAR(100) NOT NULL,
    is_active        BOOLEAN NOT NULL DEFAULT true,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.drivers (
    id             SERIAL PRIMARY KEY,
    worker_id      INTEGER NOT NULL UNIQUE REFERENCES public.workers(id),
    license_number VARCHAR(20) NOT NULL UNIQUE,
    category       VARCHAR(20),
    status_id      INTEGER NOT NULL REFERENCES public.resource_statuses(id),
    is_active      BOOLEAN NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.users (
    id            SERIAL PRIMARY KEY,
    worker_id     INTEGER NOT NULL UNIQUE REFERENCES public.workers(id),
    username      VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role_id       INTEGER NOT NULL REFERENCES public.roles(id),
    is_active     BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2.3 Tablas transaccionales -------------------------------------------------

CREATE TABLE IF NOT EXISTS public.services (
    id                SERIAL PRIMARY KEY,
    client_id         INTEGER NOT NULL REFERENCES public.clients(id),
    origin            VARCHAR(255) NOT NULL,
    destination       VARCHAR(255) NOT NULL,
    tentative_date    DATE NOT NULL,
    service_type_id   INTEGER NOT NULL REFERENCES public.service_types(id),
    cargo_type_id     INTEGER NOT NULL REFERENCES public.cargo_types(id),
    weight            NUMERIC(10,2) NOT NULL,
    length            NUMERIC(10,2),
    width             NUMERIC(10,2),
    height            NUMERIC(10,2),
    observations      TEXT,
    operational_notes TEXT,
    price             NUMERIC(10,2) NOT NULL,
    currency_id       INTEGER NOT NULL REFERENCES public.currencies(id),
    status_id         INTEGER NOT NULL REFERENCES public.service_statuses(id),
    driver_id         INTEGER REFERENCES public.drivers(id),
    tractor_id        INTEGER REFERENCES public.tractors(id),
    trailer_id        INTEGER REFERENCES public.trailers(id),
    start_date_time   TIMESTAMPTZ,
    end_date_time     TIMESTAMPTZ,
    created_by        INTEGER NOT NULL REFERENCES public.users(id),
    updated_by        INTEGER NOT NULL REFERENCES public.users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_services_status ON public.services(status_id);

CREATE TABLE IF NOT EXISTS public.service_assignments (
    id          SERIAL PRIMARY KEY,
    service_id  INTEGER NOT NULL REFERENCES public.services(id) ON DELETE CASCADE,
    truck_id    INTEGER REFERENCES public.tractors(id),
    trailer_id  INTEGER REFERENCES public.trailers(id),
    driver_id   INTEGER REFERENCES public.drivers(id),
    notes       TEXT NOT NULL,
    assigned_by INTEGER NOT NULL REFERENCES public.users(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT at_least_one_unit CHECK (
        truck_id IS NOT NULL OR trailer_id IS NOT NULL OR driver_id IS NOT NULL
    )
);

COMMENT ON COLUMN public.service_assignments.assigned_at IS
    'Timestamp de asignación con timezone (guardado en UTC, mostrado en timezone local)';
COMMENT ON COLUMN public.service_assignments.truck_id IS
    'Referencia a tractors(id)';

CREATE INDEX IF NOT EXISTS idx_service_assignments_service_id ON public.service_assignments(service_id);
CREATE INDEX IF NOT EXISTS idx_service_assignments_truck_id   ON public.service_assignments(truck_id);
CREATE INDEX IF NOT EXISTS idx_service_assignments_trailer_id ON public.service_assignments(trailer_id);
CREATE INDEX IF NOT EXISTS idx_service_assignments_driver_id  ON public.service_assignments(driver_id);

CREATE TABLE IF NOT EXISTS public.service_audit_logs (
    id          SERIAL PRIMARY KEY,
    service_id  INTEGER NOT NULL REFERENCES public.services(id) ON DELETE CASCADE,
    changed_by  INTEGER NOT NULL REFERENCES public.users(id),
    change_type VARCHAR(50) NOT NULL,
    description TEXT NOT NULL,
    "timestamp" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    old_value   VARCHAR(255),
    new_value   VARCHAR(255),
    field_name  VARCHAR(50),
    field_label VARCHAR(100)
);

COMMENT ON COLUMN public.service_audit_logs."timestamp" IS
    'Timestamp de auditoría con timezone (guardado en UTC)';

CREATE INDEX IF NOT EXISTS idx_service_audit_logs_change_type
    ON public.service_audit_logs(change_type);
CREATE INDEX IF NOT EXISTS idx_service_audit_logs_field_name
    ON public.service_audit_logs(field_name);
CREATE INDEX IF NOT EXISTS idx_service_audit_logs_service_change_type
    ON public.service_audit_logs(service_id, change_type);


-- =============================================================================
-- 3. Schema COTIZACIONES — Emisión y administración de cotizaciones
-- =============================================================================
-- Referencia tablas del schema `public` para clientes, monedas, usuarios y
-- tipos de carga (no se duplican catálogos compartidos).
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS cotizaciones;
COMMENT ON SCHEMA cotizaciones IS 'Emisión y administración de cotizaciones comerciales';

-- 3.1 Catálogos del módulo ---------------------------------------------------

CREATE TABLE IF NOT EXISTS cotizaciones.quotation_service_types (
    id          SERIAL PRIMARY KEY,
    code        VARCHAR(10) NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE cotizaciones.quotation_service_types IS
    'Tipos de servicio que aparecen en una cotización. La categoría (kind) se infiere del prefijo del `code`: S=SERVICIO, A=ALQUILER, C=COMPLEMENTARIO, I=INTEGRAL. El backend la computa al armar el response (no es columna). Notar que el `kind` SERVICIO es independiente del `quotation_type` TRANSPORTE de la cabecera — ver api/openapi.yaml para la regla del wizard.';

CREATE TABLE IF NOT EXISTS cotizaciones.payment_terms (
    id         SERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL UNIQUE,
    days       INTEGER NOT NULL DEFAULT 0,
    is_active  BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS cotizaciones.system_settings (
    key        VARCHAR(100) PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE cotizaciones.system_settings IS
    'Configuración editable del módulo de cotizaciones (condiciones estándar, validez por defecto, etc.)';

-- 3.2 Tablas transaccionales -------------------------------------------------

CREATE TABLE IF NOT EXISTS cotizaciones.quotations (
    id                     BIGSERIAL PRIMARY KEY,
    code                   VARCHAR(15) NOT NULL UNIQUE,
    quotation_type         VARCHAR(20) NOT NULL DEFAULT 'TRANSPORTE',
    status                 VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    client_id              INTEGER NOT NULL REFERENCES public.clients(id),
    contact_name           VARCHAR(200) NOT NULL,
    contact_phone          VARCHAR(9),
    currency_id            INTEGER NOT NULL REFERENCES public.currencies(id),
    payment_term_id        INTEGER REFERENCES cotizaciones.payment_terms(id),
    tentative_service_date DATE,
    validity_days          INTEGER NOT NULL DEFAULT 15,
    origin                 VARCHAR(255),
    destination            VARCHAR(255),
    created_by             INTEGER NOT NULL REFERENCES public.users(id),
    updated_by             INTEGER NOT NULL REFERENCES public.users(id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_quotations_type   CHECK (quotation_type IN ('TRANSPORTE','ALQUILER')),
    CONSTRAINT chk_quotations_status CHECK (status IN ('DRAFT','SENT')),
    CONSTRAINT chk_quotations_validity_days CHECK (validity_days > 0 AND validity_days <= 365)
);

COMMENT ON TABLE  cotizaciones.quotations               IS 'Cabecera de cotización emitida al cliente';
COMMENT ON COLUMN cotizaciones.quotations.quotation_type IS 'TRANSPORTE (con origen/destino) | ALQUILER (sin ruta, por días/unidades)';
COMMENT ON COLUMN cotizaciones.quotations.status        IS 'DRAFT (borrador interno) | SENT (enviada al cliente). Estado vencido se calcula desde created_at + validity_days';
COMMENT ON COLUMN cotizaciones.quotations.contact_name  IS 'Snapshot del contacto al momento de cotizar (obligatorio — la cotización siempre se dirige a alguien). No se reactualiza si el cliente master cambia.';
COMMENT ON COLUMN cotizaciones.quotations.contact_phone IS 'Snapshot del telefono de contacto al momento de cotizar (9 digitos numericos, no se reactualiza si el cliente cambia)';
COMMENT ON COLUMN cotizaciones.quotations.validity_days IS 'Días de validez de la cotización desde la fecha de creación';

CREATE INDEX IF NOT EXISTS idx_quotations_client     ON cotizaciones.quotations(client_id);
CREATE INDEX IF NOT EXISTS idx_quotations_code       ON cotizaciones.quotations(code);
CREATE INDEX IF NOT EXISTS idx_quotations_created_at ON cotizaciones.quotations(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_quotations_status     ON cotizaciones.quotations(status);
CREATE INDEX IF NOT EXISTS idx_quotations_type       ON cotizaciones.quotations(quotation_type);
CREATE INDEX IF NOT EXISTS idx_quotations_created_by ON cotizaciones.quotations(created_by);

-- GIN trigram para la busqueda libre `q` del listado (ILIKE %...% sobre estos campos).
-- pg_trgm ya esta habilitado (ver seccion Extensiones). Aceleran wildcards >= 3 chars.
CREATE INDEX IF NOT EXISTS idx_quotations_code_trgm        ON cotizaciones.quotations USING GIN (code gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_quotations_origin_trgm      ON cotizaciones.quotations USING GIN (origin gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_quotations_destination_trgm ON cotizaciones.quotations USING GIN (destination gin_trgm_ops);

CREATE TABLE IF NOT EXISTS cotizaciones.quotation_items (
    id                        BIGSERIAL PRIMARY KEY,
    quotation_id              BIGINT NOT NULL REFERENCES cotizaciones.quotations(id) ON DELETE CASCADE,
    parent_item_id            BIGINT REFERENCES cotizaciones.quotation_items(id) ON DELETE CASCADE,
    item_number               INTEGER NOT NULL,
    quotation_service_type_id INTEGER REFERENCES cotizaciones.quotation_service_types(id),
    cargo_type_id             INTEGER REFERENCES public.cargo_types(id),
    observations              TEXT,
    weight_kg                 NUMERIC(10,2),
    length_meters             NUMERIC(8,2),
    width_meters              NUMERIC(8,2),
    height_meters             NUMERIC(8,2),
    quantity                  INTEGER NOT NULL DEFAULT 1,
    unit_price                NUMERIC(12,2) NOT NULL DEFAULT 0,
    igv_percentage            NUMERIC(5,2) NOT NULL DEFAULT 18.00,
    insured_amount            NUMERIC(14,2),
    internal_reference_price  NUMERIC(12,2),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (quotation_id, item_number),
    CONSTRAINT chk_items_quantity   CHECK (quantity > 0),
    CONSTRAINT chk_items_unit_price CHECK (unit_price >= 0),
    CONSTRAINT chk_items_igv        CHECK (igv_percentage >= 0 AND igv_percentage <= 100),
    CONSTRAINT chk_items_no_self_parent CHECK (parent_item_id IS NULL OR parent_item_id <> id)
);

COMMENT ON TABLE  cotizaciones.quotation_items IS
    'Entidad débil dependiente de quotations (CASCADE garantiza integridad existencial). Soporta jerarquía padre-hijos vía parent_item_id para Servicio Integral.';
COMMENT ON COLUMN cotizaciones.quotation_items.parent_item_id IS
    'Self-reference para Servicio Integral: ítems hijos referencian al ítem padre que tiene el precio total. NULL para ítems independientes.';
COMMENT ON COLUMN cotizaciones.quotation_items.weight_kg IS
    'Snapshot del peso desde cargo_type (puede sobreescribirse manualmente)';
COMMENT ON COLUMN cotizaciones.quotation_items.insured_amount IS
    'Valor asegurado de la carga (solo aplica a ítems de tipo Seguro de Carga)';
COMMENT ON COLUMN cotizaciones.quotation_items.internal_reference_price IS
    'Precio interno de referencia para ítems hijos del Servicio Integral. NO se muestra en el PDF al cliente.';

CREATE INDEX IF NOT EXISTS idx_quotation_items_quotation ON cotizaciones.quotation_items(quotation_id);
CREATE INDEX IF NOT EXISTS idx_quotation_items_parent    ON cotizaciones.quotation_items(parent_item_id);
-- Para los filtros EXISTS por tipo del listado (cargoTypeId / serviceTypeId).
CREATE INDEX IF NOT EXISTS idx_quotation_items_cargo_type   ON cotizaciones.quotation_items(cargo_type_id);
CREATE INDEX IF NOT EXISTS idx_quotation_items_service_type ON cotizaciones.quotation_items(quotation_service_type_id);

CREATE TABLE IF NOT EXISTS cotizaciones.quotation_standby_costs (
    id                BIGSERIAL PRIMARY KEY,
    quotation_id      BIGINT NOT NULL REFERENCES cotizaciones.quotations(id) ON DELETE CASCADE,
    quotation_item_id BIGINT NOT NULL REFERENCES cotizaciones.quotation_items(id) ON DELETE CASCADE,
    price_per_day     NUMERIC(12,2) NOT NULL,
    includes_igv      BOOLEAN NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_quotation_item_standby UNIQUE (quotation_id, quotation_item_id)
);

COMMENT ON TABLE cotizaciones.quotation_standby_costs IS
    'Costo de stand-by por ítem de cotización (1:1 con item)';

CREATE INDEX IF NOT EXISTS idx_standby_costs_item ON cotizaciones.quotation_standby_costs(quotation_item_id);


-- =============================================================================
-- 4. Seed data
-- =============================================================================
-- El baseline crea la estructura sin datos iniciales. Los catálogos
-- (`quotation_service_types`, `payment_terms`, `system_settings`, etc.) se
-- cargan posteriormente con datos validados con el área comercial.
-- =============================================================================


-- =============================================================================
-- Fin del baseline
-- =============================================================================
