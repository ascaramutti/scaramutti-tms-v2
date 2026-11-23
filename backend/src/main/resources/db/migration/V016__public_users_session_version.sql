-- V016__public_users_session_version.sql
-- La version de las sesiones de cada cuenta: viaja en el token de renovacion y
-- se compara por igualdad, asi que subirla (al cambiar la propia contrasena)
-- corta los anteriores. Un entero y no una marca de tiempo: emitir y cambiar en
-- el mismo segundo no deja un hueco. Las cuentas quedan en 0, la que se le supone
-- a un token emitido antes, que no la trae: nadie queda afuera al desplegar.

-- users en exclusivo antes de alterarla, sin hacer cola: NOWAIT, y si esta
-- tomada se suelta y se reintenta. En la cola, el exclusivo frenaria toda
-- lectura de users, y la aplicacion la lee en cada ingreso, cada renovacion y
-- cada autor. Tras 30 s, falla.
DO $$
BEGIN
    FOR attempt IN 1..600 LOOP
        BEGIN
            LOCK TABLE public.users IN ACCESS EXCLUSIVE MODE NOWAIT;
            RETURN;
        EXCEPTION WHEN lock_not_available THEN
            PERFORM pg_sleep(0.05);
        END;
    END LOOP;
    RAISE EXCEPTION 'no se pudo tomar public.users en 30 s';
END $$;

ALTER TABLE public.users
    ADD COLUMN session_version INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_users_session_version CHECK (session_version >= 0);
