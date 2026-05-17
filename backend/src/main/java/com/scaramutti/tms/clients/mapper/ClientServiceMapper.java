package com.scaramutti.tms.clients.mapper;

import com.scaramutti.tms.clients.dto.ClientResponse;
import com.scaramutti.tms.clients.service.cmd.CreateClientCommand;
import com.scaramutti.tms.shared.entity.Client;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.util.List;

/**
 * Mapper de la capa Service:
 *  - toClientEntity: Command → Entity nueva (a persistir). Setea isActive=true
 *    explícitamente. NO setea id (lo asigna la BD) ni createdAt (lo asigna el
 *    callback @PrePersist de la entity).
 *  - toClientResponse: Entity persistida → DTO de salida.
 *  - toClientResponseList: misma transformacion en batch para los listings.
 *    MapStruct genera el loop automaticamente usando toClientResponse.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface ClientServiceMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "isActive", constant = "true")
    Client toClientEntity(CreateClientCommand createClientCommand);

    ClientResponse toClientResponse(Client client);

    List<ClientResponse> toClientResponseList(List<Client> clients);
}
