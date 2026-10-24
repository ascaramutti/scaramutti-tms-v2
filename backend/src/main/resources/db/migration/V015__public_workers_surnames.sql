-- V015__public_workers_surnames.sql
-- El apellido paterno y el materno, cada uno en su columna. last_name se queda
-- mientras la aplicacion la lea, y un disparador la mantiene de acuerdo: es el
-- paterno y, si hay, un espacio y el materno. Volver a la imagen anterior es
-- cambiar la imagen, con Flyway ignorando las migraciones futuras: no lee las
-- columnas nuevas, y lo que escribe en last_name el disparador lo copia.

-- workers en exclusivo antes de tocar nada, sin hacer cola: NOWAIT, y si esta
-- tomada se suelta y se reintenta. En la cola, el exclusivo frenaria toda
-- lectura de workers, y cada carga de una cuenta la lee. Tras 30 s, falla.
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

-- Nacen nulas para poder copiar. El paterno es last_name entero y el materno
-- queda vacio: partir por el espacio no sabe cual de dos palabras es el
-- materno. La copia no cambia ningun nombre ni tiene autor: no escribe rastro
-- ni toca updated_at ni updated_by.
ALTER TABLE public.workers
    ADD COLUMN paternal_last_name VARCHAR(100),
    ADD COLUMN maternal_last_name VARCHAR(100);

UPDATE public.workers SET paternal_last_name = last_name;

-- El materno vacio es NULL y solo NULL, ni '' ni solo espacios: asi el orden y
-- las lecturas no tienen que distinguirlos. last_name crece para que quepan
-- dos partes de 100 y el espacio; agrandar un varchar no reescribe la tabla.
ALTER TABLE public.workers
    ALTER COLUMN paternal_last_name SET NOT NULL,
    ALTER COLUMN last_name TYPE VARCHAR(201),
    ADD CONSTRAINT chk_workers_maternal_last_name_not_blank CHECK (btrim(maternal_last_name) <> '');

-- La funcion va en esta migracion y no en un R__: el disparador la necesita al
-- crearse. Mira que cambio en la fila y no que columnas nombra el UPDATE: la
-- aplicacion las reescribe todas en cada edicion, baja y reactivacion. Si
-- cambiaron las partes (en el alta, si llega alguna), ganan y last_name se
-- recompone sin el materno vacio; si cambio solo last_name, como en la imagen
-- anterior, se copia como arriba; si nada de eso, no toca nada.
CREATE FUNCTION public.workers_surnames_sync() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    parts_written BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' THEN
        parts_written := NEW.paternal_last_name IS NOT NULL OR NEW.maternal_last_name IS NOT NULL;
    ELSE
        parts_written := NEW.paternal_last_name IS DISTINCT FROM OLD.paternal_last_name
            OR NEW.maternal_last_name IS DISTINCT FROM OLD.maternal_last_name;
    END IF;
    IF parts_written THEN
        NEW.last_name := concat_ws(' ', NEW.paternal_last_name, NEW.maternal_last_name);
    ELSIF TG_OP = 'INSERT' OR NEW.last_name IS DISTINCT FROM OLD.last_name THEN
        NEW.paternal_last_name := NEW.last_name;
        NEW.maternal_last_name := NULL;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER workers_surnames_sync
    BEFORE INSERT OR UPDATE ON public.workers
    FOR EACH ROW
    EXECUTE FUNCTION public.workers_surnames_sync();
