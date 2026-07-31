package com.scaramutti.tms.operations.mapper;

import com.scaramutti.tms.operations.dto.ServiceCreateRequest;
import com.scaramutti.tms.operations.service.cmd.CreateServiceCommand;
import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.util.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Mapper de la capa REST del servicio de transporte.
 *
 * <p>Normaliza los textos libres con trim: el origen y el destino porque alimentan la busqueda
 * del listado, la comparacion de la guarda anti doble-click y lo que el usuario ve en el
 * detalle (un espacio de mas los volveria rutas "distintas"), y las observaciones que ademas
 * quedan en null cuando llegan vacias. NO se pasan a mayusculas: son nombres de lugares.
 */
@Mapper(config = SharedMapperConfig.class, uses = StringUtils.class)
public interface ServiceResourceMapper {

    @Mapping(target = "origin", source = "origin", qualifiedByName = "trimToNull")
    @Mapping(target = "destination", source = "destination", qualifiedByName = "trimToNull")
    @Mapping(target = "observations", source = "observations", qualifiedByName = "trimToNull")
    CreateServiceCommand toCreateServiceCommand(ServiceCreateRequest serviceCreateRequest);
}
