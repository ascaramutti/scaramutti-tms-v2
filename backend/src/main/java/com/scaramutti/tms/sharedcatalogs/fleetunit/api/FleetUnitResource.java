package com.scaramutti.tms.sharedcatalogs.fleetunit.api;

import com.scaramutti.tms.sharedcatalogs.fleetunit.dto.FleetUnitResponse;
import com.scaramutti.tms.sharedcatalogs.fleetunit.mapper.FleetUnitResourceMapper;
import com.scaramutti.tms.sharedcatalogs.fleetunit.service.FleetUnitService;
import com.scaramutti.tms.warehouse.model.FleetUnitKind;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Listado unificado de unidades de flota (GET /fleet-units): tractos + carretas + escoltas.
 * Path PLANO (no bajo /warehouse/*): es de {@code public} y lo reutilizara Operaciones. Sin
 * creacion al vuelo (RN-WH9: los trabajadores y las unidades de flota nunca se crean desde
 * almacen, solo se buscan). Sin paginar (flota chica). {@code kind} malformado (no matchea
 * el enum) devuelve 404 (RESTEasy, query param tipado que no parsea).
 *
 * <p>Lo consumen los dos modulos: almacen elige la unidad destino del retiro y operaciones
 * elige tracto y carreta al asignar un viaje, por eso lo alcanzan tambien {@code dispatcher}
 * (asigna recursos) y {@code sales} (registra y edita servicios).
 */
@Path("/fleet-units")
@Produces(MediaType.APPLICATION_JSON)
public class FleetUnitResource {

    @Inject FleetUnitService fleetUnitService;
    @Inject FleetUnitResourceMapper fleetUnitResourceMapper;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager", "warehouse_keeper",
        "dispatcher", "sales"})
    public List<FleetUnitResponse> listFleetUnits(
        @QueryParam("kind")     FleetUnitKind kind,
        @QueryParam("isActive") Boolean isActive
    ) {
        return fleetUnitService.listFleetUnits(
            fleetUnitResourceMapper.toListFleetUnitsQuery(kind, isActive)
        );
    }
}
