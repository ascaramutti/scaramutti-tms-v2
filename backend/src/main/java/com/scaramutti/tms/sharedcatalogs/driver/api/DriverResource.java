package com.scaramutti.tms.sharedcatalogs.driver.api;

import com.scaramutti.tms.sharedcatalogs.driver.dto.DriverResponse;
import com.scaramutti.tms.sharedcatalogs.driver.mapper.DriverResourceMapper;
import com.scaramutti.tms.sharedcatalogs.driver.service.DriverService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Listado de conductores (GET /drivers), catalogo compartido {@code public.drivers}
 * (read-only desde v2: el alta pertenece a la futura gestion de flota y personal). Path
 * PLANO, como el resto de los catalogos de {@code public}. Sin paginar (plantilla chica).
 *
 * <p>Lo alcanzan quienes asignan recursos ({@code dispatcher} y los mandos) y {@code sales},
 * que registra y edita servicios. Almacen no: el conductor no interviene en un retiro.
 */
@Path("/drivers")
@Produces(MediaType.APPLICATION_JSON)
public class DriverResource {

    @Inject DriverService driverService;
    @Inject DriverResourceMapper driverResourceMapper;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "dispatcher", "sales"})
    public List<DriverResponse> listDrivers(
        @QueryParam("isActive") Boolean isActive
    ) {
        return driverService.listDrivers(
            driverResourceMapper.toListDriversQuery(isActive)
        );
    }
}
