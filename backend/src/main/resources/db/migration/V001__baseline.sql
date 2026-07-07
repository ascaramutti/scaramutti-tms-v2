-- =============================================================================
-- V001__baseline.sql — Foto schema-only de PROD (Azure) al 2026-07-06
-- =============================================================================
-- Origen: pg_dump 16.14 --schema-only --no-owner --no-privileges de la DB de
-- produccion (verificada sin drift contra dev y contra db/baseline.sql+patches
-- ese mismo dia). NO EDITAR: una migracion aplicada nunca se modifica
-- (checksum de Flyway); cualquier cambio de schema = nueva migracion V00X.
--
-- En DBs existentes (prod/dev) Flyway NO ejecuta este archivo: baseline-on-migrate
-- marca V001 como aplicada. Solo se ejecuta completo en DBs vacias (tests/CI).
-- CREATE SCHEMA lleva IF NOT EXISTS porque Flyway pre-crea los schemas listados
-- en quarkus.flyway.schemas antes de migrar una DB vacia.
-- =============================================================================

--
-- PostgreSQL database dump
--


-- Dumped from database version 16.14
-- Dumped by pg_dump version 16.14 (Homebrew)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: cotizaciones; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA IF NOT EXISTS cotizaciones;


--
-- Name: SCHEMA cotizaciones; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA cotizaciones IS 'Emisión y administración de cotizaciones comerciales';


--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

-- *not* creating schema, since initdb creates it


--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_trgm IS 'Búsqueda fuzzy/aproximada para listados';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: conditions; Type: TABLE; Schema: cotizaciones; Owner: -
--

CREATE TABLE cotizaciones.conditions (
    id integer NOT NULL,
    text text NOT NULL,
    display_order integer NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE conditions; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON TABLE cotizaciones.conditions IS 'Catalogo versionado de condiciones generales del PDF de cotizacion (ADR-009). Fuente de verdad de las clausulas (reemplaza system_settings[''quotation.pdf_terms''] para ese fin). Inmutable por versionado: "editar" = is_active=false + INSERT nueva que hereda display_order; el `text` NUNCA se UPDATE-a (preserva el snapshot de cotizaciones ya emitidas). El marcador [[BANK_ACCOUNTS]] NO es fila de este catalogo (RN-09).';


--
-- Name: COLUMN conditions.text; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.conditions.text IS 'Texto de la clausula (cara al cliente, sale en el PDF). Inmutable: no se UPDATE-a; para cambiarlo se desactiva y se inserta una version nueva (ADR-009 §2).';


--
-- Name: COLUMN conditions.display_order; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.conditions.display_order IS 'Orden de impresion en el PDF (ASC). Una version nueva hereda el display_order de la que reemplaza. Reorder manual = diferido (DA-01).';


--
-- Name: COLUMN conditions.is_active; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.conditions.is_active IS 'Lectura (detalle/PDF) muestra activas O inactivas (snapshot, RN-05). Escritura (crear/editar) exige TODAS activas (Opcion B, RN-06 → QUO-007). El catalogo del wizard lista solo activas (RN-07).';


--
-- Name: conditions_id_seq; Type: SEQUENCE; Schema: cotizaciones; Owner: -
--

CREATE SEQUENCE cotizaciones.conditions_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: conditions_id_seq; Type: SEQUENCE OWNED BY; Schema: cotizaciones; Owner: -
--

ALTER SEQUENCE cotizaciones.conditions_id_seq OWNED BY cotizaciones.conditions.id;


--
-- Name: payment_terms; Type: TABLE; Schema: cotizaciones; Owner: -
--

CREATE TABLE cotizaciones.payment_terms (
    id integer NOT NULL,
    name character varying(100) NOT NULL,
    days integer DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: payment_terms_id_seq; Type: SEQUENCE; Schema: cotizaciones; Owner: -
--

CREATE SEQUENCE cotizaciones.payment_terms_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: payment_terms_id_seq; Type: SEQUENCE OWNED BY; Schema: cotizaciones; Owner: -
--

ALTER SEQUENCE cotizaciones.payment_terms_id_seq OWNED BY cotizaciones.payment_terms.id;


--
-- Name: quotation_conditions; Type: TABLE; Schema: cotizaciones; Owner: -
--

CREATE TABLE cotizaciones.quotation_conditions (
    quotation_id bigint NOT NULL,
    condition_id integer NOT NULL
);


--
-- Name: TABLE quotation_conditions; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON TABLE cotizaciones.quotation_conditions IS 'Tabla de apoyo FK-only (ADR-009 §3): que condiciones del catalogo aplican a cada cotizacion. PK compuesta (quotation_id, condition_id) garantiza unicidad. SIN texto: la fila inmutable de cotizaciones.conditions ES el snapshot. El PDF/detalle resuelve por JOIN ordenando por display_order ASC (RN-04), mostrando activas O inactivas (RN-05).';


--
-- Name: quotation_items; Type: TABLE; Schema: cotizaciones; Owner: -
--

CREATE TABLE cotizaciones.quotation_items (
    id bigint NOT NULL,
    quotation_id bigint NOT NULL,
    parent_item_id bigint,
    item_number integer NOT NULL,
    quotation_service_type_id integer,
    cargo_type_id integer,
    observations text,
    weight_kg numeric(10,2),
    length_meters numeric(8,2),
    width_meters numeric(8,2),
    height_meters numeric(8,2),
    quantity integer DEFAULT 1 NOT NULL,
    unit_price numeric(12,2) DEFAULT 0 NOT NULL,
    igv_percentage numeric(5,2) DEFAULT 18.00 NOT NULL,
    insured_amount numeric(14,2),
    internal_reference_price numeric(12,2),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_items_igv CHECK (((igv_percentage >= (0)::numeric) AND (igv_percentage <= (100)::numeric))),
    CONSTRAINT chk_items_no_self_parent CHECK (((parent_item_id IS NULL) OR (parent_item_id <> id))),
    CONSTRAINT chk_items_quantity CHECK ((quantity > 0)),
    CONSTRAINT chk_items_unit_price CHECK ((unit_price >= (0)::numeric))
);


--
-- Name: TABLE quotation_items; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON TABLE cotizaciones.quotation_items IS 'Entidad débil dependiente de quotations (CASCADE garantiza integridad existencial). Soporta jerarquía padre-hijos vía parent_item_id para Servicio Integral.';


--
-- Name: COLUMN quotation_items.parent_item_id; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.quotation_items.parent_item_id IS 'Self-reference para Servicio Integral: ítems hijos referencian al ítem padre que tiene el precio total. NULL para ítems independientes.';


--
-- Name: COLUMN quotation_items.weight_kg; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.quotation_items.weight_kg IS 'Snapshot del peso desde cargo_type (puede sobreescribirse manualmente)';


--
-- Name: COLUMN quotation_items.insured_amount; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.quotation_items.insured_amount IS 'Valor asegurado de la carga (solo aplica a ítems de tipo Seguro de Carga)';


--
-- Name: COLUMN quotation_items.internal_reference_price; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.quotation_items.internal_reference_price IS 'Precio interno de referencia para ítems hijos del Servicio Integral. NO se muestra en el PDF al cliente.';


--
-- Name: quotation_items_id_seq; Type: SEQUENCE; Schema: cotizaciones; Owner: -
--

CREATE SEQUENCE cotizaciones.quotation_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: quotation_items_id_seq; Type: SEQUENCE OWNED BY; Schema: cotizaciones; Owner: -
--

ALTER SEQUENCE cotizaciones.quotation_items_id_seq OWNED BY cotizaciones.quotation_items.id;


--
-- Name: quotation_service_types; Type: TABLE; Schema: cotizaciones; Owner: -
--

CREATE TABLE cotizaciones.quotation_service_types (
    id integer NOT NULL,
    code character varying(10) NOT NULL,
    name character varying(100) NOT NULL,
    description text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE quotation_service_types; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON TABLE cotizaciones.quotation_service_types IS 'Tipos de servicio que aparecen en una cotización. La categoría (kind) se infiere del prefijo del `code`: S=SERVICIO, A=ALQUILER, C=COMPLEMENTARIO, I=INTEGRAL. El backend la computa al armar el response (no es columna). Notar que el `kind` SERVICIO es independiente del `quotation_type` TRANSPORTE de la cabecera — ver api/openapi.yaml para la regla del wizard.';


--
-- Name: quotation_service_types_id_seq; Type: SEQUENCE; Schema: cotizaciones; Owner: -
--

CREATE SEQUENCE cotizaciones.quotation_service_types_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: quotation_service_types_id_seq; Type: SEQUENCE OWNED BY; Schema: cotizaciones; Owner: -
--

ALTER SEQUENCE cotizaciones.quotation_service_types_id_seq OWNED BY cotizaciones.quotation_service_types.id;


--
-- Name: quotation_standby_costs; Type: TABLE; Schema: cotizaciones; Owner: -
--

CREATE TABLE cotizaciones.quotation_standby_costs (
    id bigint NOT NULL,
    quotation_id bigint NOT NULL,
    quotation_item_id bigint NOT NULL,
    price_per_day numeric(12,2) NOT NULL,
    includes_igv boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE quotation_standby_costs; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON TABLE cotizaciones.quotation_standby_costs IS 'Costo de stand-by por ítem de cotización (1:1 con item)';


--
-- Name: quotation_standby_costs_id_seq; Type: SEQUENCE; Schema: cotizaciones; Owner: -
--

CREATE SEQUENCE cotizaciones.quotation_standby_costs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: quotation_standby_costs_id_seq; Type: SEQUENCE OWNED BY; Schema: cotizaciones; Owner: -
--

ALTER SEQUENCE cotizaciones.quotation_standby_costs_id_seq OWNED BY cotizaciones.quotation_standby_costs.id;


--
-- Name: quotations; Type: TABLE; Schema: cotizaciones; Owner: -
--

CREATE TABLE cotizaciones.quotations (
    id bigint NOT NULL,
    code character varying(15) NOT NULL,
    quotation_type character varying(20) DEFAULT 'TRANSPORTE'::character varying NOT NULL,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    client_id integer NOT NULL,
    contact_name character varying(200) NOT NULL,
    contact_phone character varying(9),
    currency_id integer NOT NULL,
    payment_term_id integer,
    tentative_service_date date,
    validity_days integer DEFAULT 15 NOT NULL,
    origin character varying(255),
    destination character varying(255),
    created_by integer NOT NULL,
    updated_by integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    client_note text,
    internal_note text,
    rejection_reason text,
    CONSTRAINT chk_quotations_client_note_len CHECK (((client_note IS NULL) OR (char_length(client_note) <= 500))),
    CONSTRAINT chk_quotations_internal_note_len CHECK (((internal_note IS NULL) OR (char_length(internal_note) <= 500))),
    CONSTRAINT chk_quotations_rejection_reason_len CHECK (((rejection_reason IS NULL) OR (char_length(rejection_reason) <= 500))),
    CONSTRAINT chk_quotations_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('SENT'::character varying)::text, ('ACCEPTED'::character varying)::text, ('REJECTED'::character varying)::text, ('EXPIRED'::character varying)::text]))),
    CONSTRAINT chk_quotations_type CHECK (((quotation_type)::text = ANY (ARRAY[('TRANSPORTE'::character varying)::text, ('ALQUILER'::character varying)::text]))),
    CONSTRAINT chk_quotations_validity_days CHECK (((validity_days > 0) AND (validity_days <= 365)))
);


--
-- Name: TABLE quotations; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON TABLE cotizaciones.quotations IS 'Cabecera de cotización emitida al cliente';


--
-- Name: COLUMN quotations.quotation_type; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.quotations.quotation_type IS 'TRANSPORTE (con origen/destino) | ALQUILER (sin ruta, por días/unidades)';


--
-- Name: COLUMN quotations.status; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.quotations.status IS 'DRAFT (borrador interno) | SENT (enviada al cliente). Estado vencido se calcula desde created_at + validity_days';


--
-- Name: COLUMN quotations.contact_name; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.quotations.contact_name IS 'Snapshot del contacto al momento de cotizar (obligatorio — la cotización siempre se dirige a alguien). No se reactualiza si el cliente master cambia.';


--
-- Name: COLUMN quotations.contact_phone; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.quotations.contact_phone IS 'Snapshot del telefono de contacto al momento de cotizar (9 digitos numericos, no se reactualiza si el cliente cambia)';


--
-- Name: COLUMN quotations.validity_days; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.quotations.validity_days IS 'Días de validez de la cotización desde la fecha de creación';


--
-- Name: COLUMN quotations.client_note; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.quotations.client_note IS 'Observación opcional visible para el cliente (PDF + UI). Texto libre, máx 500. Se renderiza escapada.';


--
-- Name: COLUMN quotations.internal_note; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.quotations.internal_note IS 'Observación opcional SOLO interna. NUNCA se renderiza en el PDF ni en salidas hacia el cliente (RN-03).';


--
-- Name: COLUMN quotations.rejection_reason; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON COLUMN cotizaciones.quotations.rejection_reason IS 'Motivo del rechazo (texto libre, max 500). Solo presente si status=REJECTED. INTERNO — NUNCA en el PDF ni salidas hacia el cliente (ADR-007, hereda regla ADR-003).';


--
-- Name: quotations_id_seq; Type: SEQUENCE; Schema: cotizaciones; Owner: -
--

CREATE SEQUENCE cotizaciones.quotations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: quotations_id_seq; Type: SEQUENCE OWNED BY; Schema: cotizaciones; Owner: -
--

ALTER SEQUENCE cotizaciones.quotations_id_seq OWNED BY cotizaciones.quotations.id;


--
-- Name: system_settings; Type: TABLE; Schema: cotizaciones; Owner: -
--

CREATE TABLE cotizaciones.system_settings (
    key character varying(100) NOT NULL,
    value text NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE system_settings; Type: COMMENT; Schema: cotizaciones; Owner: -
--

COMMENT ON TABLE cotizaciones.system_settings IS 'Configuración editable del módulo de cotizaciones (condiciones estándar, validez por defecto, etc.)';


--
-- Name: cargo_types; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cargo_types (
    id integer NOT NULL,
    name character varying(100) NOT NULL,
    description text,
    standard_weight numeric(10,2) NOT NULL,
    standard_length numeric(10,2),
    standard_width numeric(10,2),
    standard_height numeric(10,2),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: cargo_types_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.cargo_types_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: cargo_types_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.cargo_types_id_seq OWNED BY public.cargo_types.id;


--
-- Name: clients; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.clients (
    id integer NOT NULL,
    name character varying(200) NOT NULL,
    ruc character varying(11) NOT NULL,
    phone character varying(9),
    contact_name character varying(100),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: clients_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.clients_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: clients_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.clients_id_seq OWNED BY public.clients.id;


--
-- Name: currencies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.currencies (
    id integer NOT NULL,
    code character(3) NOT NULL,
    symbol character varying(5) NOT NULL,
    name character varying(50),
    is_active boolean DEFAULT true NOT NULL
);


--
-- Name: currencies_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.currencies_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: currencies_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.currencies_id_seq OWNED BY public.currencies.id;


--
-- Name: document_types; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_types (
    id integer NOT NULL,
    code character varying(10) NOT NULL,
    name character varying(50) NOT NULL,
    description text,
    max_length integer NOT NULL,
    validation_pattern character varying(255),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: document_types_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.document_types_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: document_types_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.document_types_id_seq OWNED BY public.document_types.id;


--
-- Name: drivers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.drivers (
    id integer NOT NULL,
    worker_id integer NOT NULL,
    license_number character varying(20) NOT NULL,
    category character varying(20),
    status_id integer NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: drivers_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.drivers_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: drivers_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.drivers_id_seq OWNED BY public.drivers.id;


--
-- Name: resource_statuses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.resource_statuses (
    id integer NOT NULL,
    name character varying(50) NOT NULL,
    description text,
    is_active boolean DEFAULT true NOT NULL
);


--
-- Name: resource_statuses_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.resource_statuses_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: resource_statuses_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.resource_statuses_id_seq OWNED BY public.resource_statuses.id;


--
-- Name: roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.roles (
    id integer NOT NULL,
    name character varying(50) NOT NULL,
    description text,
    is_active boolean DEFAULT true NOT NULL
);


--
-- Name: roles_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.roles_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: roles_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.roles_id_seq OWNED BY public.roles.id;


--
-- Name: service_assignments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_assignments (
    id integer NOT NULL,
    service_id integer NOT NULL,
    truck_id integer,
    trailer_id integer,
    driver_id integer,
    notes text NOT NULL,
    assigned_by integer NOT NULL,
    assigned_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT at_least_one_unit CHECK (((truck_id IS NOT NULL) OR (trailer_id IS NOT NULL) OR (driver_id IS NOT NULL)))
);


--
-- Name: COLUMN service_assignments.truck_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_assignments.truck_id IS 'Referencia a tractors(id)';


--
-- Name: COLUMN service_assignments.assigned_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_assignments.assigned_at IS 'Timestamp de asignación con timezone (guardado en UTC, mostrado en timezone local)';


--
-- Name: service_assignments_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.service_assignments_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: service_assignments_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.service_assignments_id_seq OWNED BY public.service_assignments.id;


--
-- Name: service_audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_audit_logs (
    id integer NOT NULL,
    service_id integer NOT NULL,
    changed_by integer NOT NULL,
    change_type character varying(50) NOT NULL,
    description text NOT NULL,
    "timestamp" timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    old_value character varying(255),
    new_value character varying(255),
    field_name character varying(50),
    field_label character varying(100)
);


--
-- Name: COLUMN service_audit_logs."timestamp"; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.service_audit_logs."timestamp" IS 'Timestamp de auditoría con timezone (guardado en UTC)';


--
-- Name: service_audit_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.service_audit_logs_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: service_audit_logs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.service_audit_logs_id_seq OWNED BY public.service_audit_logs.id;


--
-- Name: service_statuses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_statuses (
    id integer NOT NULL,
    name character varying(50) NOT NULL,
    description text,
    is_active boolean DEFAULT true NOT NULL
);


--
-- Name: service_statuses_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.service_statuses_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: service_statuses_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.service_statuses_id_seq OWNED BY public.service_statuses.id;


--
-- Name: service_types; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_types (
    id integer NOT NULL,
    name character varying(50) NOT NULL,
    description text,
    is_active boolean DEFAULT true NOT NULL
);


--
-- Name: service_types_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.service_types_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: service_types_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.service_types_id_seq OWNED BY public.service_types.id;


--
-- Name: services; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.services (
    id integer NOT NULL,
    client_id integer NOT NULL,
    origin character varying(255) NOT NULL,
    destination character varying(255) NOT NULL,
    tentative_date date NOT NULL,
    service_type_id integer NOT NULL,
    cargo_type_id integer NOT NULL,
    weight numeric(10,2) NOT NULL,
    length numeric(10,2),
    width numeric(10,2),
    height numeric(10,2),
    observations text,
    operational_notes text,
    price numeric(10,2) NOT NULL,
    currency_id integer NOT NULL,
    status_id integer NOT NULL,
    driver_id integer,
    tractor_id integer,
    trailer_id integer,
    start_date_time timestamp with time zone,
    end_date_time timestamp with time zone,
    created_by integer NOT NULL,
    updated_by integer NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: services_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.services_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: services_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.services_id_seq OWNED BY public.services.id;


--
-- Name: tractors; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tractors (
    id integer NOT NULL,
    plate character varying(6) NOT NULL,
    brand character varying(50),
    model character varying(50),
    year integer,
    status_id integer NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: tractors_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tractors_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tractors_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tractors_id_seq OWNED BY public.tractors.id;


--
-- Name: trailers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trailers (
    id integer NOT NULL,
    plate character varying(6) NOT NULL,
    type character varying(50) NOT NULL,
    status_id integer NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: trailers_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.trailers_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: trailers_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.trailers_id_seq OWNED BY public.trailers.id;


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id integer NOT NULL,
    worker_id integer NOT NULL,
    username character varying(50) NOT NULL,
    password_hash character varying(255) NOT NULL,
    role_id integer NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.users_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: users_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.users_id_seq OWNED BY public.users.id;


--
-- Name: workers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workers (
    id integer NOT NULL,
    first_name character varying(100) NOT NULL,
    last_name character varying(100) NOT NULL,
    document_type_id integer NOT NULL,
    document_number character varying(20) NOT NULL,
    phone character varying(9),
    "position" character varying(100) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: workers_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.workers_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: workers_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.workers_id_seq OWNED BY public.workers.id;


--
-- Name: conditions id; Type: DEFAULT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.conditions ALTER COLUMN id SET DEFAULT nextval('cotizaciones.conditions_id_seq'::regclass);


--
-- Name: payment_terms id; Type: DEFAULT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.payment_terms ALTER COLUMN id SET DEFAULT nextval('cotizaciones.payment_terms_id_seq'::regclass);


--
-- Name: quotation_items id; Type: DEFAULT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_items ALTER COLUMN id SET DEFAULT nextval('cotizaciones.quotation_items_id_seq'::regclass);


--
-- Name: quotation_service_types id; Type: DEFAULT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_service_types ALTER COLUMN id SET DEFAULT nextval('cotizaciones.quotation_service_types_id_seq'::regclass);


--
-- Name: quotation_standby_costs id; Type: DEFAULT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_standby_costs ALTER COLUMN id SET DEFAULT nextval('cotizaciones.quotation_standby_costs_id_seq'::regclass);


--
-- Name: quotations id; Type: DEFAULT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotations ALTER COLUMN id SET DEFAULT nextval('cotizaciones.quotations_id_seq'::regclass);


--
-- Name: cargo_types id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cargo_types ALTER COLUMN id SET DEFAULT nextval('public.cargo_types_id_seq'::regclass);


--
-- Name: clients id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.clients ALTER COLUMN id SET DEFAULT nextval('public.clients_id_seq'::regclass);


--
-- Name: currencies id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.currencies ALTER COLUMN id SET DEFAULT nextval('public.currencies_id_seq'::regclass);


--
-- Name: document_types id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_types ALTER COLUMN id SET DEFAULT nextval('public.document_types_id_seq'::regclass);


--
-- Name: drivers id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drivers ALTER COLUMN id SET DEFAULT nextval('public.drivers_id_seq'::regclass);


--
-- Name: resource_statuses id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resource_statuses ALTER COLUMN id SET DEFAULT nextval('public.resource_statuses_id_seq'::regclass);


--
-- Name: roles id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles ALTER COLUMN id SET DEFAULT nextval('public.roles_id_seq'::regclass);


--
-- Name: service_assignments id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_assignments ALTER COLUMN id SET DEFAULT nextval('public.service_assignments_id_seq'::regclass);


--
-- Name: service_audit_logs id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_audit_logs ALTER COLUMN id SET DEFAULT nextval('public.service_audit_logs_id_seq'::regclass);


--
-- Name: service_statuses id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_statuses ALTER COLUMN id SET DEFAULT nextval('public.service_statuses_id_seq'::regclass);


--
-- Name: service_types id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_types ALTER COLUMN id SET DEFAULT nextval('public.service_types_id_seq'::regclass);


--
-- Name: services id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.services ALTER COLUMN id SET DEFAULT nextval('public.services_id_seq'::regclass);


--
-- Name: tractors id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tractors ALTER COLUMN id SET DEFAULT nextval('public.tractors_id_seq'::regclass);


--
-- Name: trailers id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trailers ALTER COLUMN id SET DEFAULT nextval('public.trailers_id_seq'::regclass);


--
-- Name: users id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users ALTER COLUMN id SET DEFAULT nextval('public.users_id_seq'::regclass);


--
-- Name: workers id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workers ALTER COLUMN id SET DEFAULT nextval('public.workers_id_seq'::regclass);


--
-- Name: conditions conditions_pkey; Type: CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.conditions
    ADD CONSTRAINT conditions_pkey PRIMARY KEY (id);


--
-- Name: payment_terms payment_terms_name_key; Type: CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.payment_terms
    ADD CONSTRAINT payment_terms_name_key UNIQUE (name);


--
-- Name: payment_terms payment_terms_pkey; Type: CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.payment_terms
    ADD CONSTRAINT payment_terms_pkey PRIMARY KEY (id);


--
-- Name: quotation_conditions quotation_conditions_pkey; Type: CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_conditions
    ADD CONSTRAINT quotation_conditions_pkey PRIMARY KEY (quotation_id, condition_id);


--
-- Name: quotation_items quotation_items_pkey; Type: CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_items
    ADD CONSTRAINT quotation_items_pkey PRIMARY KEY (id);


--
-- Name: quotation_items quotation_items_quotation_id_item_number_key; Type: CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_items
    ADD CONSTRAINT quotation_items_quotation_id_item_number_key UNIQUE (quotation_id, item_number);


--
-- Name: quotation_service_types quotation_service_types_code_key; Type: CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_service_types
    ADD CONSTRAINT quotation_service_types_code_key UNIQUE (code);


--
-- Name: quotation_service_types quotation_service_types_pkey; Type: CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_service_types
    ADD CONSTRAINT quotation_service_types_pkey PRIMARY KEY (id);


--
-- Name: quotation_standby_costs quotation_standby_costs_pkey; Type: CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_standby_costs
    ADD CONSTRAINT quotation_standby_costs_pkey PRIMARY KEY (id);


--
-- Name: quotations quotations_code_key; Type: CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotations
    ADD CONSTRAINT quotations_code_key UNIQUE (code);


--
-- Name: quotations quotations_pkey; Type: CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotations
    ADD CONSTRAINT quotations_pkey PRIMARY KEY (id);


--
-- Name: system_settings system_settings_pkey; Type: CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.system_settings
    ADD CONSTRAINT system_settings_pkey PRIMARY KEY (key);


--
-- Name: quotation_standby_costs unique_quotation_item_standby; Type: CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_standby_costs
    ADD CONSTRAINT unique_quotation_item_standby UNIQUE (quotation_id, quotation_item_id);


--
-- Name: cargo_types cargo_types_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cargo_types
    ADD CONSTRAINT cargo_types_name_key UNIQUE (name);


--
-- Name: cargo_types cargo_types_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cargo_types
    ADD CONSTRAINT cargo_types_pkey PRIMARY KEY (id);


--
-- Name: clients clients_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.clients
    ADD CONSTRAINT clients_name_key UNIQUE (name);


--
-- Name: clients clients_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.clients
    ADD CONSTRAINT clients_pkey PRIMARY KEY (id);


--
-- Name: clients clients_ruc_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.clients
    ADD CONSTRAINT clients_ruc_key UNIQUE (ruc);


--
-- Name: currencies currencies_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.currencies
    ADD CONSTRAINT currencies_code_key UNIQUE (code);


--
-- Name: currencies currencies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.currencies
    ADD CONSTRAINT currencies_pkey PRIMARY KEY (id);


--
-- Name: document_types document_types_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_types
    ADD CONSTRAINT document_types_code_key UNIQUE (code);


--
-- Name: document_types document_types_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_types
    ADD CONSTRAINT document_types_pkey PRIMARY KEY (id);


--
-- Name: drivers drivers_license_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drivers
    ADD CONSTRAINT drivers_license_number_key UNIQUE (license_number);


--
-- Name: drivers drivers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drivers
    ADD CONSTRAINT drivers_pkey PRIMARY KEY (id);


--
-- Name: drivers drivers_worker_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drivers
    ADD CONSTRAINT drivers_worker_id_key UNIQUE (worker_id);


--
-- Name: resource_statuses resource_statuses_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resource_statuses
    ADD CONSTRAINT resource_statuses_name_key UNIQUE (name);


--
-- Name: resource_statuses resource_statuses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resource_statuses
    ADD CONSTRAINT resource_statuses_pkey PRIMARY KEY (id);


--
-- Name: roles roles_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_name_key UNIQUE (name);


--
-- Name: roles roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);


--
-- Name: service_assignments service_assignments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_assignments
    ADD CONSTRAINT service_assignments_pkey PRIMARY KEY (id);


--
-- Name: service_audit_logs service_audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_audit_logs
    ADD CONSTRAINT service_audit_logs_pkey PRIMARY KEY (id);


--
-- Name: service_statuses service_statuses_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_statuses
    ADD CONSTRAINT service_statuses_name_key UNIQUE (name);


--
-- Name: service_statuses service_statuses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_statuses
    ADD CONSTRAINT service_statuses_pkey PRIMARY KEY (id);


--
-- Name: service_types service_types_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_types
    ADD CONSTRAINT service_types_name_key UNIQUE (name);


--
-- Name: service_types service_types_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_types
    ADD CONSTRAINT service_types_pkey PRIMARY KEY (id);


--
-- Name: services services_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.services
    ADD CONSTRAINT services_pkey PRIMARY KEY (id);


--
-- Name: tractors tractors_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tractors
    ADD CONSTRAINT tractors_pkey PRIMARY KEY (id);


--
-- Name: tractors tractors_plate_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tractors
    ADD CONSTRAINT tractors_plate_key UNIQUE (plate);


--
-- Name: trailers trailers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trailers
    ADD CONSTRAINT trailers_pkey PRIMARY KEY (id);


--
-- Name: trailers trailers_plate_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trailers
    ADD CONSTRAINT trailers_plate_key UNIQUE (plate);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: users users_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);


--
-- Name: users users_worker_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_worker_id_key UNIQUE (worker_id);


--
-- Name: workers workers_document_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workers
    ADD CONSTRAINT workers_document_number_key UNIQUE (document_number);


--
-- Name: workers workers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workers
    ADD CONSTRAINT workers_pkey PRIMARY KEY (id);


--
-- Name: idx_conditions_display_order; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_conditions_display_order ON cotizaciones.conditions USING btree (display_order);


--
-- Name: idx_quotation_conditions_condition; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotation_conditions_condition ON cotizaciones.quotation_conditions USING btree (condition_id);


--
-- Name: idx_quotation_items_cargo_type; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotation_items_cargo_type ON cotizaciones.quotation_items USING btree (cargo_type_id);


--
-- Name: idx_quotation_items_parent; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotation_items_parent ON cotizaciones.quotation_items USING btree (parent_item_id);


--
-- Name: idx_quotation_items_quotation; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotation_items_quotation ON cotizaciones.quotation_items USING btree (quotation_id);


--
-- Name: idx_quotation_items_service_type; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotation_items_service_type ON cotizaciones.quotation_items USING btree (quotation_service_type_id);


--
-- Name: idx_quotations_client; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotations_client ON cotizaciones.quotations USING btree (client_id);


--
-- Name: idx_quotations_code; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotations_code ON cotizaciones.quotations USING btree (code);


--
-- Name: idx_quotations_code_trgm; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotations_code_trgm ON cotizaciones.quotations USING gin (code public.gin_trgm_ops);


--
-- Name: idx_quotations_created_at; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotations_created_at ON cotizaciones.quotations USING btree (created_at DESC);


--
-- Name: idx_quotations_created_by; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotations_created_by ON cotizaciones.quotations USING btree (created_by);


--
-- Name: idx_quotations_destination_trgm; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotations_destination_trgm ON cotizaciones.quotations USING gin (destination public.gin_trgm_ops);


--
-- Name: idx_quotations_origin_trgm; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotations_origin_trgm ON cotizaciones.quotations USING gin (origin public.gin_trgm_ops);


--
-- Name: idx_quotations_status; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotations_status ON cotizaciones.quotations USING btree (status);


--
-- Name: idx_quotations_type; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_quotations_type ON cotizaciones.quotations USING btree (quotation_type);


--
-- Name: idx_standby_costs_item; Type: INDEX; Schema: cotizaciones; Owner: -
--

CREATE INDEX idx_standby_costs_item ON cotizaciones.quotation_standby_costs USING btree (quotation_item_id);


--
-- Name: idx_cargo_types_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cargo_types_name_trgm ON public.cargo_types USING gin (name public.gin_trgm_ops);


--
-- Name: idx_clients_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_clients_name_trgm ON public.clients USING gin (name public.gin_trgm_ops);


--
-- Name: idx_clients_ruc_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_clients_ruc_trgm ON public.clients USING gin (ruc public.gin_trgm_ops);


--
-- Name: idx_service_assignments_driver_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_assignments_driver_id ON public.service_assignments USING btree (driver_id);


--
-- Name: idx_service_assignments_service_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_assignments_service_id ON public.service_assignments USING btree (service_id);


--
-- Name: idx_service_assignments_trailer_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_assignments_trailer_id ON public.service_assignments USING btree (trailer_id);


--
-- Name: idx_service_assignments_truck_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_assignments_truck_id ON public.service_assignments USING btree (truck_id);


--
-- Name: idx_service_audit_logs_change_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_audit_logs_change_type ON public.service_audit_logs USING btree (change_type);


--
-- Name: idx_service_audit_logs_field_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_audit_logs_field_name ON public.service_audit_logs USING btree (field_name);


--
-- Name: idx_service_audit_logs_service_change_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_service_audit_logs_service_change_type ON public.service_audit_logs USING btree (service_id, change_type);


--
-- Name: idx_services_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_services_status ON public.services USING btree (status_id);


--
-- Name: quotation_conditions quotation_conditions_condition_id_fkey; Type: FK CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_conditions
    ADD CONSTRAINT quotation_conditions_condition_id_fkey FOREIGN KEY (condition_id) REFERENCES cotizaciones.conditions(id);


--
-- Name: quotation_conditions quotation_conditions_quotation_id_fkey; Type: FK CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_conditions
    ADD CONSTRAINT quotation_conditions_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES cotizaciones.quotations(id) ON DELETE CASCADE;


--
-- Name: quotation_items quotation_items_cargo_type_id_fkey; Type: FK CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_items
    ADD CONSTRAINT quotation_items_cargo_type_id_fkey FOREIGN KEY (cargo_type_id) REFERENCES public.cargo_types(id);


--
-- Name: quotation_items quotation_items_parent_item_id_fkey; Type: FK CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_items
    ADD CONSTRAINT quotation_items_parent_item_id_fkey FOREIGN KEY (parent_item_id) REFERENCES cotizaciones.quotation_items(id) ON DELETE CASCADE;


--
-- Name: quotation_items quotation_items_quotation_id_fkey; Type: FK CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_items
    ADD CONSTRAINT quotation_items_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES cotizaciones.quotations(id) ON DELETE CASCADE;


--
-- Name: quotation_items quotation_items_quotation_service_type_id_fkey; Type: FK CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_items
    ADD CONSTRAINT quotation_items_quotation_service_type_id_fkey FOREIGN KEY (quotation_service_type_id) REFERENCES cotizaciones.quotation_service_types(id);


--
-- Name: quotation_standby_costs quotation_standby_costs_quotation_id_fkey; Type: FK CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_standby_costs
    ADD CONSTRAINT quotation_standby_costs_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES cotizaciones.quotations(id) ON DELETE CASCADE;


--
-- Name: quotation_standby_costs quotation_standby_costs_quotation_item_id_fkey; Type: FK CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotation_standby_costs
    ADD CONSTRAINT quotation_standby_costs_quotation_item_id_fkey FOREIGN KEY (quotation_item_id) REFERENCES cotizaciones.quotation_items(id) ON DELETE CASCADE;


--
-- Name: quotations quotations_client_id_fkey; Type: FK CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotations
    ADD CONSTRAINT quotations_client_id_fkey FOREIGN KEY (client_id) REFERENCES public.clients(id);


--
-- Name: quotations quotations_created_by_fkey; Type: FK CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotations
    ADD CONSTRAINT quotations_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: quotations quotations_currency_id_fkey; Type: FK CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotations
    ADD CONSTRAINT quotations_currency_id_fkey FOREIGN KEY (currency_id) REFERENCES public.currencies(id);


--
-- Name: quotations quotations_payment_term_id_fkey; Type: FK CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotations
    ADD CONSTRAINT quotations_payment_term_id_fkey FOREIGN KEY (payment_term_id) REFERENCES cotizaciones.payment_terms(id);


--
-- Name: quotations quotations_updated_by_fkey; Type: FK CONSTRAINT; Schema: cotizaciones; Owner: -
--

ALTER TABLE ONLY cotizaciones.quotations
    ADD CONSTRAINT quotations_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.users(id);


--
-- Name: drivers drivers_status_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drivers
    ADD CONSTRAINT drivers_status_id_fkey FOREIGN KEY (status_id) REFERENCES public.resource_statuses(id);


--
-- Name: drivers drivers_worker_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drivers
    ADD CONSTRAINT drivers_worker_id_fkey FOREIGN KEY (worker_id) REFERENCES public.workers(id);


--
-- Name: service_assignments service_assignments_assigned_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_assignments
    ADD CONSTRAINT service_assignments_assigned_by_fkey FOREIGN KEY (assigned_by) REFERENCES public.users(id);


--
-- Name: service_assignments service_assignments_driver_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_assignments
    ADD CONSTRAINT service_assignments_driver_id_fkey FOREIGN KEY (driver_id) REFERENCES public.drivers(id);


--
-- Name: service_assignments service_assignments_service_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_assignments
    ADD CONSTRAINT service_assignments_service_id_fkey FOREIGN KEY (service_id) REFERENCES public.services(id) ON DELETE CASCADE;


--
-- Name: service_assignments service_assignments_trailer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_assignments
    ADD CONSTRAINT service_assignments_trailer_id_fkey FOREIGN KEY (trailer_id) REFERENCES public.trailers(id);


--
-- Name: service_assignments service_assignments_truck_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_assignments
    ADD CONSTRAINT service_assignments_truck_id_fkey FOREIGN KEY (truck_id) REFERENCES public.tractors(id);


--
-- Name: service_audit_logs service_audit_logs_changed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_audit_logs
    ADD CONSTRAINT service_audit_logs_changed_by_fkey FOREIGN KEY (changed_by) REFERENCES public.users(id);


--
-- Name: service_audit_logs service_audit_logs_service_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_audit_logs
    ADD CONSTRAINT service_audit_logs_service_id_fkey FOREIGN KEY (service_id) REFERENCES public.services(id) ON DELETE CASCADE;


--
-- Name: services services_cargo_type_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.services
    ADD CONSTRAINT services_cargo_type_id_fkey FOREIGN KEY (cargo_type_id) REFERENCES public.cargo_types(id);


--
-- Name: services services_client_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.services
    ADD CONSTRAINT services_client_id_fkey FOREIGN KEY (client_id) REFERENCES public.clients(id);


--
-- Name: services services_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.services
    ADD CONSTRAINT services_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: services services_currency_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.services
    ADD CONSTRAINT services_currency_id_fkey FOREIGN KEY (currency_id) REFERENCES public.currencies(id);


--
-- Name: services services_driver_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.services
    ADD CONSTRAINT services_driver_id_fkey FOREIGN KEY (driver_id) REFERENCES public.drivers(id);


--
-- Name: services services_service_type_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.services
    ADD CONSTRAINT services_service_type_id_fkey FOREIGN KEY (service_type_id) REFERENCES public.service_types(id);


--
-- Name: services services_status_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.services
    ADD CONSTRAINT services_status_id_fkey FOREIGN KEY (status_id) REFERENCES public.service_statuses(id);


--
-- Name: services services_tractor_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.services
    ADD CONSTRAINT services_tractor_id_fkey FOREIGN KEY (tractor_id) REFERENCES public.tractors(id);


--
-- Name: services services_trailer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.services
    ADD CONSTRAINT services_trailer_id_fkey FOREIGN KEY (trailer_id) REFERENCES public.trailers(id);


--
-- Name: services services_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.services
    ADD CONSTRAINT services_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.users(id);


--
-- Name: tractors tractors_status_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tractors
    ADD CONSTRAINT tractors_status_id_fkey FOREIGN KEY (status_id) REFERENCES public.resource_statuses(id);


--
-- Name: trailers trailers_status_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trailers
    ADD CONSTRAINT trailers_status_id_fkey FOREIGN KEY (status_id) REFERENCES public.resource_statuses(id);


--
-- Name: users users_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id);


--
-- Name: users users_worker_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_worker_id_fkey FOREIGN KEY (worker_id) REFERENCES public.workers(id);


--
-- Name: workers workers_document_type_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workers
    ADD CONSTRAINT workers_document_type_id_fkey FOREIGN KEY (document_type_id) REFERENCES public.document_types(id);


--
-- PostgreSQL database dump complete
--


