package com.scaramutti.tms.cargotypes.mapper;

import com.scaramutti.tms.cargotypes.dto.CargoTypeResponse;
import com.scaramutti.tms.cargotypes.service.cmd.CreateCargoTypeCommand;
import com.scaramutti.tms.shared.entity.CargoType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.util.List;

/**
 * Mapper de la capa Service:
 *  - toCargoTypeEntity: Command → Entity nueva (a persistir). Setea isActive=true
 *    explicitamente. NO setea id (lo asigna la BD) ni createdAt (lo asigna el
 *    callback @PrePersist de la entity). En este metodo el target SI tiene
 *    `createdAt` — por eso esta el `@Mapping(target="createdAt", ignore=true)`.
 *  - toCargoTypeResponse: Entity → DTO. El target (CargoTypeResponse) NO tiene
 *    `createdAt`, asi que MapStruct lo omite silenciosamente — no requiere
 *    @Mapping explicito.
 *  - toCargoTypeResponseList: batch usando toCargoTypeResponse.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface CargoTypeServiceMapper {

    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "isActive",  constant = "true")
    CargoType toCargoTypeEntity(CreateCargoTypeCommand createCargoTypeCommand);

    CargoTypeResponse toCargoTypeResponse(CargoType cargoType);

    List<CargoTypeResponse> toCargoTypeResponseList(List<CargoType> cargoTypes);
}
