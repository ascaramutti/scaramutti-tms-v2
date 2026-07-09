package com.scaramutti.tms.warehouse.supplier.mapper;

import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.warehouse.supplier.service.cmd.ListWarehouseSuppliersQuery;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import org.mapstruct.NullValueMappingStrategy;

/**
 * Mapper de la capa REST. `q` (listing) se normaliza trim + uppercase:
 * similarity() del ranking es case-sensitive y name se almacena en mayúsculas.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.CDI,
    uses = StringUtils.class,
    nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface WarehouseSupplierResourceMapper {

    @Mapping(target = "q", source = "q", qualifiedByName = "trimUpperOrNull")
    ListWarehouseSuppliersQuery toListWarehouseSuppliersQuery(String q, Boolean isActive, int page, int size);

    @Named("trimUpperOrNull")
    default String trimUpperOrNull(String value) {
        String trimmed = StringUtils.trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase();
    }
}
