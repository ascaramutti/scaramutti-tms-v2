package com.scaramutti.tms.clients.service.cmd;

/**
 * Command interno del service para la edicion (PUT /clients/{id}).
 *
 * Los strings llegan aca YA NORMALIZADOS por el ResourceMapper, con las MISMAS
 * reglas del alta:
 *  - name: trimmed + uppercase (asi se almacena en BD)
 *  - ruc, phone: tal cual (Bean Validation ya garantiza solo digitos)
 *  - contactName: trimmed, y "" se normaliza a null
 *
 * El id NO viaja aca: va como parametro de {@code updateClient(Integer, ...)}.
 * Es lo que hace `WarehouseProductService.updateProduct`, y ademas deja claro
 * que el id identifica al recurso y no es un dato editable.
 *
 * Es un command propio y no {@code CreateClientCommand} reusado: el del alta
 * alimenta un mapper que fija {@code isActive = true}, y la edicion tiene que
 * dejar ese campo sin tocar.
 */
public record UpdateClientCommand(String name, String ruc, String phone, String contactName) {}
