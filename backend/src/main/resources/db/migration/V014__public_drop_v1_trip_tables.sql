-- V014__public_drop_v1_trip_tables.sql
-- Borra las cinco tablas de viajes de la v1: services, sus asignaciones, su
-- auditoria, sus estados y sus tipos. El cutover de la v2.4.0 paso los viajes
-- a operaciones, con sus notas y su auditoria, y desde entonces nadie las lee
-- ni las escribe.

-- Volver atras es restaurar el respaldo tomado antes de aplicarla.

-- Antes de tocar nada, en exclusivo y sin hacer cola, todas las tablas que el
-- borrado toma: las cinco y las siete vivas a las que apuntan sus claves
-- foraneas. NOWAIT, y si alguna esta tomada se suelta todo y se reintenta: en la
-- cola, el exclusivo frena toda lectura de la tabla y se abraza con quien lea
-- dos de ellas en el orden contrario. Tras 30 s, falla nombrando la ultima tomada.
DO $$
DECLARE
    last_error TEXT;
BEGIN
    FOR attempt IN 1..600 LOOP
        BEGIN
            LOCK TABLE public.service_audit_logs, public.service_assignments, public.services,
                public.service_statuses, public.service_types, public.users, public.clients,
                public.drivers, public.tractors, public.trailers, public.cargo_types,
                public.currencies
                IN ACCESS EXCLUSIVE MODE NOWAIT;
            RETURN;
        EXCEPTION WHEN lock_not_available THEN
            last_error := SQLERRM;
            PERFORM pg_sleep(0.05);
        END;
    END LOOP;
    RAISE EXCEPTION 'no se pudieron tomar en 30 s las cinco tablas de la v1 y las siete a las que apuntan (%)',
        last_error;
END $$;

DROP TABLE public.service_audit_logs, public.service_assignments, public.services,
    public.service_statuses, public.service_types;
