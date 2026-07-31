package com.scaramutti.tms.sharedcatalogs.driver.mapper;

import com.scaramutti.tms.shared.mapper.SharedMapperConfig;
import com.scaramutti.tms.shared.repository.DriverRepository.DriverRow;
import com.scaramutti.tms.sharedcatalogs.driver.dto.DriverResponse;
import com.scaramutti.tms.sharedcatalogs.model.FleetResourceStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Mapper de la capa Service: fila del listado de conductores a DriverResponse. La
 * disponibilidad llega como el nombre crudo del catalogo de v1 (en minusculas) y la traduce
 * {@link FleetResourceStatus#fromCatalogName}, que revienta ante un nombre desconocido en vez
 * de dejarlo pasar como null.
 */
@Mapper(config = SharedMapperConfig.class)
public interface DriverServiceMapper {

    @Mapping(target = "status", source = "statusName")
    DriverResponse toDriverResponse(DriverRow row);

    List<DriverResponse> toDriverResponseList(List<DriverRow> rows);

    default FleetResourceStatus toFleetResourceStatus(String catalogName) {
        return FleetResourceStatus.fromCatalogName(catalogName);
    }
}
