-- V008__operaciones_services_deleted_status.sql
-- Amplia el dominio del estado de operaciones.services con DELETED.
--
-- DELETED es la etiqueta del registro que nunca debio existir (un error de
-- digitacion: el servicio se cargo por equivocacion y se borra de la vista
-- operativa). Es distinto de CANCELLED, que marca un viaje real que se aborto y
-- que el negocio quiere seguir viendo en el historial. Asi quedo en el contrato
-- ya fusionado en api/openapi.yaml.
--
-- La DB solo abre el dominio: las transiciones validas (solo desde los dos
-- estados pendientes) las valida el backend cuando exista el endpoint de
-- transiciones. Hoy ningun codigo escribe este valor.
--
-- V007 ya fue aplicada por la cadena de Flyway (su checksum queda registrado y
-- una migracion aplicada jamas se edita), por eso el cambio va en una migracion
-- nueva: se suelta el CHECK y se recrea con el valor agregado.

ALTER TABLE operaciones.services DROP CONSTRAINT chk_services_status;

ALTER TABLE operaciones.services ADD CONSTRAINT chk_services_status CHECK (status IN
    ('PENDING_ASSIGNMENT','PENDING_START','IN_PROGRESS','COMPLETED','CANCELLED','DELETED'));
