package com.scaramutti.tms.clients.mapper;

import com.scaramutti.tms.clients.dto.ClientResponse;
import com.scaramutti.tms.clients.service.cmd.CreateClientCommand;
import com.scaramutti.tms.clients.service.cmd.UpdateClientCommand;
import com.scaramutti.tms.shared.entity.Client;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * Mapper de la capa Service:
 *  - toClientEntity: Command → Entity nueva (a persistir). Setea isActive=true
 *    explícitamente. NO setea id (lo asigna la BD) ni createdAt (lo asigna el
 *    callback @PrePersist de la entity).
 *  - toClientResponse: Entity persistida → DTO de salida.
 *  - toClientResponseList: misma transformacion en batch para los listings.
 *    MapStruct genera el loop automaticamente usando toClientResponse.
 *  - applyUpdate: Command → Entity YA persistida (edicion). Escribe solo los
 *    cuatro campos editables en memoria; que la COLUMNA is_active tampoco se
 *    reescriba depende del `updatable = false` de la entidad, no de este mapper.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface ClientServiceMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "isActive", constant = "true")
    Client toClientEntity(CreateClientCommand createClientCommand);

    ClientResponse toClientResponse(Client client);

    /**
     * Aplica la edicion sobre la entidad gestionada. Los tres `ignore` dicen que
     * el id identifica la fila y que `isActive` y `createdAt` no viajan en el
     * cuerpo. Desactivar un cliente es otra operacion.
     *
     * OJO con lo que estos `ignore` NO hacen: protegen el campo EN MEMORIA, no la
     * columna. Que el UPDATE no lleve `is_active` lo consigue el `updatable = false`
     * de la entidad, y el comentario de esa columna explica por que.
     *
     * Ojo con copiar de {@code toClientEntity}: ese fija `isActive = true` porque
     * da de alta. Traer esa linea aca reactivaria en silencio a un cliente
     * desactivado cada vez que alguien corrige su telefono.
     */
    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "isActive",  ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    void applyUpdate(@MappingTarget Client client, UpdateClientCommand updateClientCommand);

    List<ClientResponse> toClientResponseList(List<Client> clients);
}
