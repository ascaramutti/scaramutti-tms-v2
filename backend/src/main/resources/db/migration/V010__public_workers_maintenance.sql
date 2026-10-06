-- V010__public_workers_maintenance.sql
-- Mantenimiento de trabajadores desde la aplicacion: el cargo es el rol
-- (public.roles con nivel, modalidad de ficha y roles que no inician sesion),
-- el trabajador apunta a su rol, fecha de ingreso, columnas de auditoria y
-- tabla de auditoria de trabajadores.
--
-- Aditiva sobre public: agrega columnas, filas de catalogo, restricciones y
-- una tabla nueva; no renombra ni borra nada. La columna workers.position
-- queda sin NOT NULL y sin lector (la aplicacion deja de mapearla); se borra
-- en una migracion posterior, cuando una release ya corrio en produccion con
-- este esquema y ya no haga falta la imagen anterior para un rollback.
--
-- Ensayada en seco el 2026-09-20 en una base descartable copiada de la de dev
-- (8 trabajadores, 7 roles): aplica completa en una transaccion y la guarda
-- del bloque 2d aborta sin dejar nada aplicado ante un texto desconocido.
-- Antes de aplicarla a produccion se mide en staging (copia diaria) que
-- ningun rol ni ningun texto de cargo quede fuera de las listas de abajo.

-- =============================================================================
-- 1. Una sola jerarquia: public.roles gana nivel, "inicia sesion" y modalidad
--    de ficha; suma los cuatro roles que nunca inician sesion (RN-06, RN-09,
--    RN-11; PRD decision 12)
-- =============================================================================
-- level: nivel del organigrama (4 a 1). Sin DEFAULT a proposito: todo rol
--   nuevo nace con su nivel (PRD RN-09); un INSERT que lo omita falla.
-- can_login: si el rol puede tener usuario. Los siete existentes si; los
--   cuatro nuevos no. No se deduce del nivel: el encargado de almacen es de
--   nivel 1 y si inicia sesion. El modulo de usuarios lo consulta al crear uno.
-- driver_profile: si el rol lleva ficha de conductor: REQUIRED (obligatoria),
--   OPTIONAL (solo si viene la licencia), NONE (la ficha se rechaza).
ALTER TABLE public.roles
    ADD COLUMN level          SMALLINT,
    ADD COLUMN can_login      BOOLEAN     NOT NULL DEFAULT true,
    ADD COLUMN driver_profile VARCHAR(10) NOT NULL DEFAULT 'NONE';

-- Las once filas del organigrama (PRD seccion 4). Para las siete existentes el
-- ON CONFLICT actualiza nivel, inicia-sesion, modalidad y DESCRIPCION: desde
-- esta unidad la descripcion es el nombre visible del cargo (lo que muestran
-- el pie del menu, la firma de auditoria y el PDF de cotizacion), asi que se
-- fija al texto de la tabla del PRD. No toca is_active ni id. La descripcion
-- actual de cada rol en produccion la lista medicion-staging.sql, bloque 1.
INSERT INTO public.roles (name, description, is_active, level, can_login, driver_profile) VALUES
    ('admin',              'Administrador del Sistema',  true, 4, true,  'NONE'),
    ('general_manager',    'Gerente General',            true, 3, true,  'NONE'),
    ('operations_manager', 'Gerente de Operaciones',     true, 3, true,  'NONE'),
    ('finance_manager',    'Jefe de Finanzas',           true, 2, true,  'NONE'),
    ('dispatcher',         'Coordinador de Operaciones', true, 2, true,  'NONE'),
    ('sales',              'Ejecutivo de Ventas',        true, 2, true,  'NONE'),
    ('warehouse_keeper',   'Encargado de Almacén',       true, 1, true,  'NONE'),
    ('driver',             'Conductor',                  true, 1, false, 'REQUIRED'),
    ('escort',             'Escolta',                    true, 1, false, 'REQUIRED'),
    ('assistant',          'Ayudante',                   true, 1, false, 'OPTIONAL'),
    ('operator',           'Operador',                   true, 1, false, 'NONE')
ON CONFLICT (name) DO UPDATE
    SET description    = EXCLUDED.description,
        level          = EXCLUDED.level,
        can_login      = EXCLUDED.can_login,
        driver_profile = EXCLUDED.driver_profile;

-- Un rol que no este en la lista de arriba quedo sin nivel y hace fallar el
-- NOT NULL: a proposito, un rol sorpresa tiene que verse en staging (copia
-- diaria de produccion) y decidirse, no recibir un nivel por defecto.
ALTER TABLE public.roles
    ALTER COLUMN level SET NOT NULL,
    ADD CONSTRAINT chk_roles_level CHECK (level BETWEEN 1 AND 4),
    ADD CONSTRAINT chk_roles_driver_profile CHECK (driver_profile IN ('REQUIRED', 'OPTIONAL', 'NONE'));

-- =============================================================================
-- 2. El trabajador apunta a su rol (RN-06, RN-15)
-- =============================================================================
ALTER TABLE public.workers
    ADD COLUMN role_id INTEGER REFERENCES public.roles(id);

-- 2a. Primero los trabajadores con usuario: llevan el rol de su usuario, sea
--     cual sea el texto de position (RN-15: rol del trabajador = rol del
--     usuario). Deterministico y sin nombrar a nadie.
UPDATE public.workers w
    SET role_id = u.role_id
   FROM public.users u
  WHERE u.worker_id = w.id;

-- 2b. Despues, los demas por el texto de position contra la descripcion del
--     rol, sin distinguir mayusculas ni espacios en los bordes. Cubre los
--     seis valores de produccion (medido 2026-09-18) y los cuatro nombres
--     nuevos.
UPDATE public.workers w
    SET role_id = r.id
   FROM public.roles r
  WHERE w.role_id IS NULL
    AND lower(btrim(w.position)) = lower(r.description);

-- 2c. Sinonimos que no coinciden con ninguna descripcion: la forma anterior
--     de un rol ('Jefa de Finanzas', descripcion de finance_manager desde la
--     V002) y los textos que existen solo en bases de desarrollo (seeder y
--     fixtures de test: medido el 2026-09-18, ninguno esta en produccion; en
--     produccion este bloque es no-op). Sin estas lineas la migracion tumba el
--     backend de dev de quien no recree su base.
UPDATE public.workers w
    SET role_id = r.id
   FROM public.roles r
  WHERE w.role_id IS NULL
    AND r.name = CASE lower(btrim(w.position))
        WHEN 'jefa de finanzas'    THEN 'finance_manager'
        WHEN 'ejecutiva de ventas' THEN 'sales'
        WHEN 'encargado de ventas' THEN 'sales'
        WHEN 'inactivo de prueba'  THEN 'operator'
        WHEN 'ztest operario'      THEN 'operator'
        WHEN 'mecánico'            THEN 'operator'
        WHEN 'mecanico'            THEN 'operator'
        WHEN 'chofer'              THEN 'driver'
        WHEN 'almacenero'          THEN 'warehouse_keeper'
    END;

-- 2d. Guarda legible: si quedo algun trabajador sin rol, aborta con la lista
--     de textos en el mensaje (el fallo del NOT NULL solo diria "viola la
--     restriccion"). A proposito NO hay rol por defecto: un cargo sorpresa
--     tiene que verse en staging y no asignarse en silencio.
DO $$
DECLARE
    offending TEXT;
BEGIN
    SELECT string_agg(DISTINCT quote_literal(position), ', ')
      INTO offending
      FROM public.workers
     WHERE role_id IS NULL;
    IF offending IS NOT NULL THEN
        RAISE EXCEPTION 'workers.position sin rol equivalente en public.roles: %', offending;
    END IF;
END $$;

ALTER TABLE public.workers
    ALTER COLUMN role_id SET NOT NULL;

CREATE INDEX idx_workers_role ON public.workers(role_id);

-- 2e. position deja de ser obligatoria: la aplicacion ya no la escribe ni la
--     lee (el cargo se lee de roles.description a traves de role_id). Los
--     valores existentes se conservan para que la imagen anterior del backend
--     siga arrancando si hay que volver atras; la columna se borra en una
--     migracion posterior.
ALTER TABLE public.workers
    ALTER COLUMN position DROP NOT NULL;

-- =============================================================================
-- 3. Fecha de ingreso (RN-13)
-- =============================================================================
-- Distinta de created_at: created_at es cuando se grabo la fila; hire_date es
-- cuando la persona entro a la empresa. Para las filas existentes se copia el
-- dia calendario de Lima en que se grabaron (por nombre de zona, nunca por
-- offset: Peru no tiene horario de verano hoy, pero el nombre sobrevive a que
-- lo tenga) y el dueno corrige a mano desde la pantalla. Sin DEFAULT: la
-- aplicacion la manda siempre; una fila sin fecha de ingreso es un error.
ALTER TABLE public.workers
    ADD COLUMN hire_date DATE;

UPDATE public.workers
    SET hire_date = (created_at AT TIME ZONE 'America/Lima')::date
    WHERE hire_date IS NULL;

ALTER TABLE public.workers
    ALTER COLUMN hire_date SET NOT NULL;

-- =============================================================================
-- 4. Quien creo y quien modifico (RN-12, RN-14)
-- =============================================================================
-- Molde: cotizaciones.quotations (created_by, updated_by, updated_at). Las dos
-- referencias a users quedan NULLABLES a proposito, por dos motivos que no
-- dependen uno del otro:
--   (a) las filas existentes se cargaron por SQL desde el sistema anterior y
--       nadie sabe quien las creo: escribir un usuario ahi seria un hecho falso
--       en una columna de auditoria, asi que quedan en NULL;
--   (b) en una base vacia el primer trabajador se crea ANTES que el primer
--       usuario (el usuario exige un trabajador), asi que un NOT NULL con FK a
--       users no se puede satisfacer nunca para esa primera fila.
-- Para las filas nuevas la aplicacion los pone siempre.
ALTER TABLE public.workers
    ADD COLUMN created_by INTEGER REFERENCES public.users(id),
    ADD COLUMN updated_by INTEGER REFERENCES public.users(id);

-- updated_at: se agrega con DEFAULT (misma clase que created_at, para que un
-- INSERT nativo que la omita no falle), se rellena con created_at (una fila que
-- nunca se edito tiene por version su fecha de alta; mismo criterio que la
-- V006 con withdrawals) y recien entonces se marca NOT NULL.
ALTER TABLE public.workers
    ADD COLUMN updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP;

UPDATE public.workers
    SET updated_at = created_at;

ALTER TABLE public.workers
    ALTER COLUMN updated_at SET NOT NULL;

-- =============================================================================
-- 5. Tabla de auditoria de trabajadores (RN-14, RN-15)
-- =============================================================================
-- Calcada de almacen.audit_logs con dos adaptaciones: worker_id con FK en vez
-- del par entity_type/entity_id (hay una sola entidad; es lo que hizo
-- operaciones.service_audit_logs), y reason NULLABLE porque el motivo es
-- obligatorio solo cuando cambia el numero de documento, y eso lo exige la
-- aplicacion. Tambien registra las cascadas sobre users (rol y estado) como
-- filas del trabajador: la unica prueba de por que una cuenta cambio de
-- permisos o dejo de entrar. Se escribe desde el dia 1; la pantalla de
-- historial queda para despues.
CREATE TABLE public.worker_audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    worker_id   INTEGER NOT NULL REFERENCES public.workers(id),
    change_type VARCHAR(30) NOT NULL,   -- CREATED | FIELD_EDIT | DEACTIVATED | REACTIVATED
    field_name  VARCHAR(50),            -- firstName, documentNumber, role, user.role, driver.licenseNumber, ...
    field_label VARCHAR(100),           -- etiqueta en espanol para la futura pantalla
    old_value   TEXT,
    new_value   TEXT,
    reason      TEXT,                   -- obligatorio (>= 10) solo al cambiar el documento
    changed_by  INTEGER NOT NULL REFERENCES public.users(id),
    logged_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_worker_audit_change CHECK (change_type IN
        ('CREATED','FIELD_EDIT','DEACTIVATED','REACTIVATED'))
);
CREATE INDEX idx_worker_audit_worker ON public.worker_audit_logs(worker_id);
