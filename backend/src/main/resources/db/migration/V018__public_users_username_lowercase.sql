-- V018__public_users_username_lowercase.sql
-- El nombre de usuario siempre en minusculas: con la regla validada, la restriccion
-- unica que ya existe alcanza para que dos cuentas no difieran solo en mayusculas.
-- No va NOT VALID: rige igual en todo UPDATE posterior, y una cuenta heredada en
-- mayusculas no aceptaria ningun cambio, ni de contrasena ni de estado. Por eso la
-- guarda de abajo aborta si hay alguna, y decide una persona.

-- users en exclusivo antes de alterarla, sin hacer cola, por lo mismo que la V016.
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

-- Guarda legible, con la tabla ya tomada para que nadie escriba entre la guarda y
-- la restriccion. A proposito no corrige nada: el ingreso compara el nombre exacto,
-- y cambiarle el nombre a alguien es una decision. Lista solo ids, nunca nombres.
DO $$
DECLARE
    with_uppercase TEXT;
BEGIN
    SELECT string_agg(id::text, ', ' ORDER BY id)
      INTO with_uppercase
      FROM public.users
     WHERE username <> lower(username);
    IF with_uppercase IS NOT NULL THEN
        RAISE EXCEPTION 'cuentas con mayusculas en el nombre de usuario (users.id): %', with_uppercase;
    END IF;
END $$;

ALTER TABLE public.users
    ADD CONSTRAINT chk_users_username_lowercase CHECK (username = lower(username));
