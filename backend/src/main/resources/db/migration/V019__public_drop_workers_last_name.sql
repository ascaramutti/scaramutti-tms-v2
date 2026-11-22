-- V019__public_drop_workers_last_name.sql
-- Borra last_name, el apellido entero, con el disparador que la componia desde
-- las partes y su funcion. Era la composicion de las partes: no se pierde nada.
-- Volver a la imagen anterior sigue siendo cambiar la imagen, porque ya no la
-- mapea; volver a una que la lea es restaurar el respaldo tomado antes de aplicar
-- esta migracion.

-- workers en exclusivo antes de tocar nada, sin hacer cola, por lo mismo que la V015.
DO $$
BEGIN
    FOR attempt IN 1..600 LOOP
        BEGIN
            LOCK TABLE public.workers IN ACCESS EXCLUSIVE MODE NOWAIT;
            RETURN;
        EXCEPTION WHEN lock_not_available THEN
            PERFORM pg_sleep(0.05);
        END;
    END LOOP;
    RAISE EXCEPTION 'no se pudo tomar public.workers en 30 s';
END $$;

-- Sin CASCADE ni IF EXISTS: si una vista depende de la columna o falta alguno de
-- los tres, la migracion aborta en vez de seguir de largo.
DROP TRIGGER workers_surnames_sync ON public.workers;

DROP FUNCTION public.workers_surnames_sync();

ALTER TABLE public.workers DROP COLUMN last_name;
