-- V009__operaciones_services_price_greater_than_zero.sql
-- Endurece el precio del servicio: de "no negativo" a "mayor que cero".
--
-- El >= 0 venia del DDL de diseno sin decision detras. El contrato y la validacion
-- del alta ya exigen un precio mayor que cero; esta migracion cierra la tercera
-- capa, la de la base, para que ninguna otra via de escritura (un script, una
-- carga manual) pueda dejar un viaje sin precio.
--
-- OJO con la carga de datos historicos: el sistema anterior NO tiene esta
-- restriccion en su base y su edicion no valida el precio (solo su alta lo
-- rechaza, y por accidente), asi que puede grabar un cero mientras siga vivo. Al
-- escribir esto ninguno de sus 905 servicios tiene precio cero, pero eso es una
-- foto de una fuente que sigue escribiendo: RE-VERIFICARLO antes de la carga.
--
-- V007 ya fue aplicada por la cadena de Flyway y una migracion aplicada jamas se
-- edita, por eso el cambio va aca: se suelta el CHECK y se recrea endurecido.

ALTER TABLE operaciones.services DROP CONSTRAINT chk_services_price;

ALTER TABLE operaciones.services ADD CONSTRAINT chk_services_price CHECK (price > 0);
