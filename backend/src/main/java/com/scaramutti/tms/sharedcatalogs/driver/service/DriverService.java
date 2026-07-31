package com.scaramutti.tms.sharedcatalogs.driver.service;

import com.scaramutti.tms.shared.repository.DriverRepository;
import com.scaramutti.tms.sharedcatalogs.driver.dto.DriverResponse;
import com.scaramutti.tms.sharedcatalogs.driver.mapper.DriverServiceMapper;
import com.scaramutti.tms.sharedcatalogs.driver.service.cmd.ListDriversQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Listado de conductores (GET /drivers). Read-only, sin {@code @Transactional}. Los joins
 * (nombre del trabajador, disponibilidad), el filtro y el orden los resuelve
 * {@link DriverRepository#search}; el shaping al response vive en el mapper.
 */
@ApplicationScoped
public class DriverService {

    @Inject DriverRepository driverRepository;
    @Inject DriverServiceMapper driverServiceMapper;

    public List<DriverResponse> listDrivers(ListDriversQuery query) {
        return driverServiceMapper.toDriverResponseList(
            driverRepository.search(query.isActive())
        );
    }
}
