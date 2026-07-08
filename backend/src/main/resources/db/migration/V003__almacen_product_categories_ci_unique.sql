-- V003__almacen_product_categories_ci_unique.sql
-- Cierra RN-WH10 (unicidad case-insensitive de categorias de producto) a
-- nivel de base de datos: el UNIQUE plano que trae V002 en
-- almacen.product_categories.name es case-sensitive (Postgres distingue
-- 'Filtros' de 'filtros'), lo que deja una ventana de carrera entre el
-- check-then-act de la app y el INSERT (dos requests casi simultaneas con
-- distinto casing podrian ambas pasar la validacion de la app).
--
-- Un indice unico funcional sobre lower(name) lo cierra de raiz: Postgres
-- rechaza el segundo INSERT aunque llegue en el mismo instante, sin ventana
-- posible. El UNIQUE plano original de V002 queda intacto (nunca se edita
-- una migracion aplicada) y es inofensivo: si lower(name) es unico, name
-- tambien lo es, asi que no colisiona con el indice nuevo.
CREATE UNIQUE INDEX uq_product_categories_name_ci
    ON almacen.product_categories (lower(name));
