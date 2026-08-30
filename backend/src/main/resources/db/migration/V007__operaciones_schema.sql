-- V007__operaciones_schema.sql
-- Schema del modulo Operaciones (control de viajes; recodificacion de v1).
-- Fuente de diseno (verdad): v1-operations/05_ESQUEMA_OPERACIONES.md +
-- v1-operations/06_CONTRATO_OPERACIONES.md. Copiado literal salvo las
-- correcciones ratificadas por el dueno el 2026-07-29 (anotadas caso por caso).
--
-- v1 NO se toca: sus tablas siguen vivas sobre public hasta el cutover, y este
-- modulo no modifica ningun objeto de public (solo lo referencia por FK).

-- =============================================================================
-- 1. Schema
-- =============================================================================
CREATE SCHEMA IF NOT EXISTS operaciones;
COMMENT ON SCHEMA operaciones IS 'Control de viajes (recodificacion de v1)';

-- =============================================================================
-- 2. Nucleo: services
-- =============================================================================
CREATE TABLE operaciones.services (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(15) NOT NULL,             -- SRV-0001 (nuevo: v1 solo tenia id; la UI usa codigo)
    client_id       INTEGER NOT NULL REFERENCES public.clients(id),
    origin          VARCHAR(255) NOT NULL,
    destination     VARCHAR(255) NOT NULL,
    tentative_date  DATE NOT NULL,                    -- pasada PERMITIDA (registro retroactivo, la UI avisa)
    -- AMBITO del viaje (ex public.service_types de v1: 2 filas Local | Provincia).
    -- Es un dominio CERRADO sin CRUD ni atributos -> columna + CHECK, mismo
    -- patron que el estado (y que QuotationStatus en cotizaciones). No hay tabla
    -- trip_scopes ni FK: el catalogo administrable seria mentira, nadie lo edita.
    trip_scope      VARCHAR(10) NOT NULL,
    cargo_type_id   INTEGER NOT NULL REFERENCES public.cargo_types(id),
    weight          NUMERIC(10,2) NOT NULL,
    length          NUMERIC(10,2),
    width           NUMERIC(10,2),
    height          NUMERIC(10,2),
    observations    TEXT,
    -- (SIN operational_notes: reemplazado por operaciones.service_events)
    price           NUMERIC(12,2) NOT NULL,
    currency_id     INTEGER NOT NULL REFERENCES public.currencies(id),
    -- ESTADO como columna + CHECK. El enum de Quarkus lleva las transiciones:
    -- PENDING_ASSIGNMENT -> PENDING_START -> IN_PROGRESS -> COMPLETED, y
    -- CANCELLED desde los tres no-terminales.
    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING_ASSIGNMENT',
    driver_id       INTEGER REFERENCES public.drivers(id),
    tractor_id      INTEGER REFERENCES public.tractors(id),
    trailer_id      INTEGER REFERENCES public.trailers(id),
    start_date_time TIMESTAMPTZ,
    end_date_time   TIMESTAMPTZ,
    created_by      INTEGER NOT NULL REFERENCES public.users(id),
    updated_by      INTEGER NOT NULL REFERENCES public.users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_services_status CHECK (status IN
        ('PENDING_ASSIGNMENT','PENDING_START','IN_PROGRESS','COMPLETED','CANCELLED')),
    CONSTRAINT chk_services_trip_scope CHECK (trip_scope IN ('LOCAL','PROVINCIA')),
    CONSTRAINT chk_services_weight CHECK (weight > 0),
    CONSTRAINT chk_services_price  CHECK (price >= 0),
    CONSTRAINT chk_services_dims   CHECK (
        (length IS NULL OR length > 0) AND (width IS NULL OR width > 0) AND (height IS NULL OR height > 0)),
    -- v1 nunca valido el cruce de fechas (hay servicios historicos con
    -- end < start): el cutover los corrige ANTES de insertarlos.
    CONSTRAINT chk_services_times  CHECK (
        end_date_time IS NULL OR start_date_time IS NULL OR end_date_time >= start_date_time)
);

-- Unicidad case-insensitive del codigo (convencion de la casa desde V005): el
-- backend lo genera siempre en mayusculas, el indice impide que un INSERT manual
-- o el script de cutover cuelen 'srv-0001' como un codigo distinto.
CREATE UNIQUE INDEX uq_op_services_code_ci ON operaciones.services (lower(code));

-- Indices de las queries calientes:
CREATE INDEX idx_op_services_client   ON operaciones.services(client_id);
CREATE INDEX idx_op_services_driver   ON operaciones.services(driver_id);   -- conflictos de asignacion
CREATE INDEX idx_op_services_tractor  ON operaciones.services(tractor_id);  -- conflictos de asignacion
CREATE INDEX idx_op_services_trailer  ON operaciones.services(trailer_id);  -- conflictos de asignacion
CREATE INDEX idx_op_services_date     ON operaciones.services(tentative_date);
CREATE INDEX idx_op_services_status_end ON operaciones.services(status, end_date_time); -- reporte semanal

-- Busqueda libre del listado (codigo/ruta), como quotations y almacen. El
-- operador va CALIFICADO (public.gin_trgm_ops): la extension pg_trgm vive en
-- public y el search_path de Flyway no la alcanza en una DB fresca (CI).
CREATE INDEX idx_op_services_code_trgm ON operaciones.services USING GIN (code public.gin_trgm_ops);
CREATE INDEX idx_op_services_orig_trgm ON operaciones.services USING GIN (origin public.gin_trgm_ops);
CREATE INDEX idx_op_services_dest_trgm ON operaciones.services USING GIN (destination public.gin_trgm_ops);

-- =============================================================================
-- 3. Eventos operativos (reemplazan el operational_notes concatenado de v1)
-- =============================================================================
-- Cada nota/observacion del viaje = UNA fila (asignar/iniciar/finalizar/nota
-- suelta): consultable, ordenable y atribuible. event_type existe para que la UI
-- pinte cada evento con su badge sin adivinar parseando el texto. La bitacora
-- historica que llega del cutover entra como NOTE.
CREATE TABLE operaciones.service_events (
    id         BIGSERIAL PRIMARY KEY,
    service_id BIGINT NOT NULL REFERENCES operaciones.services(id) ON DELETE CASCADE,
    event_type VARCHAR(20) NOT NULL DEFAULT 'NOTE',
    note       TEXT NOT NULL,
    created_by INTEGER NOT NULL REFERENCES public.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_op_events_type CHECK (event_type IN
        ('CREATED','ASSIGNMENT','STATUS_CHANGE','FIELD_EDIT','NOTE'))
);
CREATE INDEX idx_op_events_service ON operaciones.service_events(service_id);

-- =============================================================================
-- 4. Recursos adicionales (service_assignments saneada)
-- =============================================================================
CREATE TABLE operaciones.service_assignments (
    id          BIGSERIAL PRIMARY KEY,
    service_id  BIGINT NOT NULL REFERENCES operaciones.services(id) ON DELETE CASCADE,
    tractor_id  INTEGER REFERENCES public.tractors(id),   -- unificado (v1 lo llamaba truck_id)
    trailer_id  INTEGER REFERENCES public.trailers(id),
    driver_id   INTEGER REFERENCES public.drivers(id),
    reason      TEXT NOT NULL,                            -- motivo del refuerzo (>= 10, lo valida la app)
    assigned_by INTEGER NOT NULL REFERENCES public.users(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_op_assign_one_unit CHECK (
        tractor_id IS NOT NULL OR trailer_id IS NOT NULL OR driver_id IS NOT NULL)
);
CREATE INDEX idx_op_assign_service ON operaciones.service_assignments(service_id);
CREATE INDEX idx_op_assign_tractor ON operaciones.service_assignments(tractor_id);
CREATE INDEX idx_op_assign_trailer ON operaciones.service_assignments(trailer_id);
CREATE INDEX idx_op_assign_driver  ON operaciones.service_assignments(driver_id);

-- =============================================================================
-- 5. Auditoria del modulo
-- =============================================================================
-- Tabla propia (NO se generaliza la de almacen: el shape es distinto, esta
-- cuelga de un solo tipo de entidad y guarda la justificacion de la edicion).
-- Se escribe desde el dia 1; mostrarla en la UI quedo diferido por el dueno.
CREATE TABLE operaciones.service_audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    service_id  BIGINT NOT NULL REFERENCES operaciones.services(id) ON DELETE CASCADE,
    changed_by  INTEGER NOT NULL REFERENCES public.users(id),
    change_type VARCHAR(50) NOT NULL,
    field_name  VARCHAR(50),
    field_label VARCHAR(100),
    old_value   TEXT,                   -- TEXT y no VARCHAR(255): v1 truncaba
    new_value   TEXT,
    description TEXT NOT NULL,          -- justificacion de la edicion (>= 10)
    logged_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- v1 la llamaba "timestamp" (palabra reservada)
    -- ADMIN_UPDATE es historico de v1: el cutover lo conserva literal, asi que
    -- entra al dominio permitido aunque el backend nuevo nunca lo escriba.
    CONSTRAINT chk_op_audit_change CHECK (change_type IN
        ('CREATED','ASSIGNMENT','STATUS_CHANGE','FIELD_EDIT','ADMIN_UPDATE'))
);
CREATE INDEX idx_op_audit_service_type ON operaciones.service_audit_logs(service_id, change_type);
