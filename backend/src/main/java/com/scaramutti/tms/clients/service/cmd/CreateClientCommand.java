package com.scaramutti.tms.clients.service.cmd;

/**
 * Command interno del service. Desacopla la capa REST del dominio Clients.
 *
 * Los strings llegan acá YA NORMALIZADOS por el ResourceMapper:
 *  - name: trimmed + uppercase (asi se almacena en BD)
 *  - ruc, phone: trimmed
 *  - contactName: trimmed, y "" se normaliza a null
 *
 * El service puede asumir estos invariantes sin re-normalizar.
 */
public record CreateClientCommand(String name, String ruc, String phone, String contactName) {}
