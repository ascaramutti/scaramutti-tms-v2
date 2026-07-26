-- V004__almacen_suppliers_ci_unique.sql
-- Cierra RN-WH10 (unicidad case-insensitive de name/ruc de proveedores) a
-- nivel de base de datos, mismo criterio que V003 para
-- almacen.product_categories.name: los UNIQUE planos que trae V002 en
-- almacen.suppliers.name y almacen.suppliers.ruc son case-sensitive, lo que
-- deja una ventana de carrera entre el check-then-act de la app y el INSERT.
--
-- ruc es numerico (11 digitos), por lo que en la practica no tiene variantes
-- de casing, pero el contrato (RN-WH10) pide el mismo tratamiento que name
-- explicitamente — el indice es coherente y no tiene costo real.
--
-- Los UNIQUE planos originales de V002 quedan intactos (nunca se edita una
-- migracion aplicada) y son inofensivos: si lower(x) es unico, x tambien lo
-- es, asi que no colisionan con los indices nuevos.
CREATE UNIQUE INDEX uq_suppliers_name_ci
    ON almacen.suppliers (lower(name));

CREATE UNIQUE INDEX uq_suppliers_ruc_ci
    ON almacen.suppliers (lower(ruc))
    WHERE ruc IS NOT NULL;
