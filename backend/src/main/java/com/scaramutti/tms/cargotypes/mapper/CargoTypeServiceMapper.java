package com.scaramutti.tms.cargotypes.mapper;

import com.scaramutti.tms.cargotypes.dto.CargoTypeResponse;
import com.scaramutti.tms.shared.entity.CargoType;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

/**
 * Mapper de la capa Service: traduce entities del dominio a DTOs de salida.
 *
 * `toCargoTypeResponse` ignora `createdAt` de la entity (no esta en el DTO).
 * MapStruct genera el mapping field-by-field; `createdAt` simplemente no se
 * mapea porque el target no lo tiene.
 *
 * `toCargoTypeResponseList` genera el batch automaticamente usando el de single.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.CDI)
public interface CargoTypeServiceMapper {

    CargoTypeResponse toCargoTypeResponse(CargoType cargoType);

    List<CargoTypeResponse> toCargoTypeResponseList(List<CargoType> cargoTypes);
}
