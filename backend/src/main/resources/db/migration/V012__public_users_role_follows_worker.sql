-- V012__public_users_role_follows_worker.sql
-- La cuenta toma el rol de su trabajador: dos disparadores mantienen
-- users.role_id igual a workers.role_id del trabajador de la cuenta, sin
-- depender de que cada escritura copie el valor. La columna se conserva: la
-- imagen anterior de la aplicacion saca de ahi los permisos, y volver a ella
-- sigue siendo cambiar la imagen (con Flyway ignorando las migraciones futuras).

-- Nadie escribe en las dos tablas hasta el final de la migracion, asi ninguna
-- escritura se cuela entre la guarda y los disparadores: la que estaba en
-- curso termina antes y la guarda la ve. Van en el orden en que escribe la
-- aplicacion, primero la cuenta (Hibernate ordena los UPDATE por entidad y
-- User va antes que Worker); al reves, con una edicion en curso PostgreSQL
-- abortaria una de las dos, y si es la migracion falla el arranque.
LOCK TABLE public.users, public.workers IN SHARE ROW EXCLUSIVE MODE;

-- Guarda legible: si alguna cuenta tiene un rol distinto del cargo de su
-- trabajador, aborta con la lista de cuentas. A proposito no elige cual de los
-- dos vale: uno de ellos da permisos que el otro no, y eso lo decide una
-- persona antes de aplicar.
DO $$
DECLARE
    offending TEXT;
BEGIN
    SELECT string_agg(u.id::text, ', ' ORDER BY u.id)
      INTO offending
      FROM public.users u
      JOIN public.workers w ON w.id = u.worker_id
     WHERE u.role_id <> w.role_id;
    IF offending IS NOT NULL THEN
        RAISE EXCEPTION 'cuentas con un rol distinto del cargo de su trabajador (users.id): %', offending;
    END IF;
END $$;

-- Las funciones van en esta migracion y no en un R__: los repetibles corren
-- despues de todas las versionadas, y el disparador necesita su funcion al
-- crearse.

-- La cuenta nueva, o aquella cuyo rol o trabajador se escribe, toma el rol del
-- trabajador. Se fija y no se valida: cuando la aplicacion deje de escribir la
-- columna, el alta llega sin rol y lo pone este disparador. FOR SHARE espera a
-- quien tenga tomada la fila del trabajador (un cambio de cargo, por ejemplo) y
-- toma el valor confirmado. Si el trabajador no existe no toca nada: el alta
-- falla por la clave foranea si trae rol, y por el NOT NULL si no.
CREATE FUNCTION public.users_role_from_worker() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    worker_role INTEGER;
BEGIN
    SELECT role_id INTO worker_role FROM public.workers WHERE id = NEW.worker_id FOR SHARE;
    IF FOUND THEN
        NEW.role_id := worker_role;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER users_role_from_worker
    BEFORE INSERT OR UPDATE OF role_id, worker_id ON public.users
    FOR EACH ROW
    EXECUTE FUNCTION public.users_role_from_worker();

-- Cambiar el cargo del trabajador cambia el rol de su cuenta en la misma
-- transaccion. Corre solo si el cargo cambio: la aplicacion reescribe la
-- columna en cada edicion del trabajador.
CREATE FUNCTION public.workers_role_to_account() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE public.users SET role_id = NEW.role_id WHERE worker_id = NEW.id;
    RETURN NULL;
END $$;

CREATE TRIGGER workers_role_to_account
    AFTER UPDATE OF role_id ON public.workers
    FOR EACH ROW
    WHEN (NEW.role_id IS DISTINCT FROM OLD.role_id)
    EXECUTE FUNCTION public.workers_role_to_account();
