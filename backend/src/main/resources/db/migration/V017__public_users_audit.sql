-- V017__public_users_audit.sql
-- Quien creo y quien modifico por ultima vez cada cuenta, y el rastro de sus cambios.
-- created_by y updated_by quedan nulos en las cuentas existentes: nadie sabe quien las
-- cargo, y firmarlas con el administrador seria un hecho falso en una columna de autoria.
-- updated_at no se actualiza solo: lo mueve cada cambio que deja rastro de la cuenta, y no
-- toda escritura de la fila (el sembrador de dev la reescribe sin ser un cambio de nadie).

-- users en exclusivo antes de alterarla, sin hacer cola, por lo mismo que la V016. Es la
-- unica tabla existente que toma el archivo: las claves foraneas nuevas apuntan a ella.
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

-- Molde de la V010: nullables tambien porque la primera cuenta de una base vacia no tiene
-- quien la cree, y updated_at nace con el valor de created_at antes de pasar a obligatoria.
ALTER TABLE public.users
    ADD COLUMN created_by INTEGER REFERENCES public.users(id),
    ADD COLUMN updated_by INTEGER REFERENCES public.users(id),
    ADD COLUMN updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP;

UPDATE public.users
   SET updated_at = created_at;

ALTER TABLE public.users
    ALTER COLUMN updated_at SET NOT NULL;

-- El rastro de las cuentas, calcado de worker_audit_logs (V010) con la cuenta en lugar del
-- trabajador y sin motivo: ningun cambio de una cuenta lo pide. Nunca guarda una
-- contrasena ni su hash: las filas de la contrasena van sin valores.
CREATE TABLE public.user_audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES public.users(id),
    change_type VARCHAR(30) NOT NULL,
    field_name  VARCHAR(50),            -- username, isActive, password
    field_label VARCHAR(100),           -- etiqueta en espanol para la futura pantalla
    old_value   TEXT,
    new_value   TEXT,
    changed_by  INTEGER NOT NULL REFERENCES public.users(id),
    logged_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Los cambios de cuentas de esta version; corregir el usuario y desbloquear suman su
    -- tipo con su propia migracion, cuando lleguen.
    CONSTRAINT chk_user_audit_change CHECK (change_type IN
        ('CREATED','DEACTIVATED','REACTIVATED','PASSWORD_RESET','PASSWORD_CHANGED'))
);
CREATE INDEX idx_user_audit_user ON public.user_audit_logs(user_id);
