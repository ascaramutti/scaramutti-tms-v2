package com.scaramutti.tms.shared.mapper;

import org.mapstruct.MapperConfig;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Config compartida de todos los mappers MapStruct del backend.
 *
 * unmappedTargetPolicy = ERROR es la red del patrón "responses via mapper":
 * si un campo del target (DTO/entity) queda sin mapear, la COMPILACIÓN falla
 * en vez de dejar el campo en null en silencio. Todo campo intencionalmente
 * sin mapear se declara con @Mapping(target = ..., ignore = true), que además
 * documenta quién lo resuelve (BD, @PrePersist, service).
 */
@MapperConfig(
    componentModel = MappingConstants.ComponentModel.CDI,
    unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface SharedMapperConfig {
}
