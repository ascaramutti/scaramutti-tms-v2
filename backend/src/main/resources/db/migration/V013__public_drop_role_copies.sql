-- V013__public_drop_role_copies.sql
-- Borra las dos columnas que repetian el cargo del trabajador. users.role_id
-- ya no la lee nadie: la cuenta toma el rol del cargo de su trabajador, y los
-- disparadores de la V012 que la mantenian igual al cargo se van con ella.
-- workers.position es el texto del cargo que la V010 dejo sin lector.

-- Lo que la V012 decia y deja de valer: volver a la imagen anterior ya no es
-- cambiar la imagen, porque esa imagen lee users.role_id. Volver atras es
-- restaurar el respaldo tomado antes de aplicar esta migracion.

-- Las tres tablas en exclusivo antes de tocar nada (roles, por la clave foranea
-- que se va con users.role_id), sin hacer cola: NOWAIT, y si alguna esta tomada
-- se suelta todo y se reintenta. En la cola, el exclusivo frena toda lectura de
-- la tabla y se abraza con quien lea dos de ellas en el orden contrario, y la
-- aplicacion lee en los dos (el ingreso, la cuenta y despues el trabajador; la
-- baja, al reves). Tras 30 s, falla.
DO $$
BEGIN
    FOR attempt IN 1..600 LOOP
        BEGIN
            LOCK TABLE public.workers, public.users, public.roles
                IN ACCESS EXCLUSIVE MODE NOWAIT;
            RETURN;
        EXCEPTION WHEN lock_not_available THEN
            PERFORM pg_sleep(0.05);
        END;
    END LOOP;
    RAISE EXCEPTION 'no se pudieron tomar public.workers, public.users y public.roles en 30 s';
END $$;

DROP TRIGGER users_role_from_worker ON public.users;
DROP TRIGGER workers_role_to_account ON public.workers;

DROP FUNCTION public.users_role_from_worker();
DROP FUNCTION public.workers_role_to_account();

ALTER TABLE public.users DROP COLUMN role_id;
ALTER TABLE public.workers DROP COLUMN position;
