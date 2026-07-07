-- Roles que el dump de prod (Azure) referencia y que no existen en un
-- postgres vanilla. Sin esto, el restore escupe errores de "role does not
-- exist" en los OWNER TO / GRANT del dump.
-- (El rol OWNER de los objetos del dump ya existe: es el POSTGRES_USER
--  del contenedor, DB_USERNAME del env file.)
CREATE ROLE azure_pg_admin NOLOGIN;
