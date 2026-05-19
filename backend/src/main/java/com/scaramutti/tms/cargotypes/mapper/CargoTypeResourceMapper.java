package com.scaramutti.tms.cargotypes.mapper;

import com.scaramutti.tms.cargotypes.service.cmd.ListCargoTypesQuery;
import com.scaramutti.tms.shared.util.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST: traduce los query-params HTTP a un Query del service.
 *
 * Normaliza `q` con trim + uppercase, "" → null. Se uppercasea aqui porque
 * los nombres de cargo-types se almacenan en mayusculas en BD y el operador
 * `similarity()` del ranking es case-sensitive (aunque ILIKE no lo es, el
 * upper homogeneiza la comparacion).
 *
 * `uses = StringUtils.class` permite referenciar `StringUtils.trimToNull`
 * directamente via `qualifiedByName`.
 *
 * `nullValueMappingStrategy = RETURN_DEFAULT` (mapper-level): cuando todos
 * los source params nullable son null (caso `GET /cargo-types` sin
 * query-params), MapStruct construye el target con defaults en vez de
 * devolver null. Critico para el happy path por defecto.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.CDI,
    uses = StringUtils.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface CargoTypeResourceMapper {

    @Mapping(target = "q", source = "q", qualifiedByName = "trimUpperOrNull")
    ListCargoTypesQuery toListCargoTypesQuery(String q, Boolean isActive, int page, int size);

    /**
     * Trim + uppercase. Delega normalizacion vacio → null a `StringUtils.trimToNull`.
     */
    @Named("trimUpperOrNull")
    default String trimUpperOrNull(String value) {
        String trimmed = StringUtils.trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase();
    }
}
