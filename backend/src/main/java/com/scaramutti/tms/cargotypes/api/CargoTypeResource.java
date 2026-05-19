package com.scaramutti.tms.cargotypes.api;

import com.scaramutti.tms.cargotypes.dto.CargoTypeRequest;
import com.scaramutti.tms.cargotypes.dto.CargoTypeResponse;
import com.scaramutti.tms.cargotypes.mapper.CargoTypeResourceMapper;
import com.scaramutti.tms.cargotypes.service.CargoTypeService;
import com.scaramutti.tms.shared.dto.PageResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;

@Path("/cargo-types")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CargoTypeResource {

    @Inject CargoTypeService cargoTypeService;
    @Inject CargoTypeResourceMapper cargoTypeResourceMapper;

    /**
     * Sin @RolesAllowed: el contrato listCargoTypes no tiene `x-required-roles`,
     * cualquier autenticado puede listar. La policy global protected-paths
     * cubre el caso authn (sin token → 401).
     *
     * Bean Validation en query-params: violaciones disparan
     * ConstraintViolationException → ValidationExceptionMapper → 400 COM-001.
     *
     * `@Size(min=3, max=200)` en `q` valida el minimo de busqueda esperado
     * por el combobox del frontend. Defense-in-depth: si el front tiene un bug
     * y manda q="ab", backend rechaza. Tambien previene queries patologicos
     * en BD grandes (q="a" daria miles de matches por ILIKE).
     */
    @GET
    public PageResponse<CargoTypeResponse> listCargoTypes(
        @QueryParam("q")        @Size(min = 3, max = 200)         String q,
        @QueryParam("isActive")                                   Boolean isActive,
        @QueryParam("page")     @DefaultValue("0")  @Min(0)       int page,
        @QueryParam("size")     @DefaultValue("20") @Min(1) @Max(100) int size
    ) {
        return cargoTypeService.listCargoTypes(
            cargoTypeResourceMapper.toListCargoTypesQuery(q, isActive, page, size)
        );
    }

    /**
     * Crea un tipo de carga al vuelo desde el wizard de cotizaciones.
     * @RolesAllowed: admin/general_manager/sales/operations_manager pueden crear;
     * dispatcher NO (mismo criterio que POST /clients). Si dispatcher llama,
     * Quarkus tira io.quarkus.security.ForbiddenException → ForbiddenExceptionMapper
     * → 403 COM-003.
     */
    @POST
    @RolesAllowed({"admin", "general_manager", "sales", "operations_manager"})
    @ResponseStatus(201) // Response.Status.CREATED — el default de JAX-RS para POST que retorna body es 200, lo sobreescribimos.
    public CargoTypeResponse createCargoType(@Valid CargoTypeRequest cargoTypeRequest) {
        return cargoTypeService.createCargoType(
            cargoTypeResourceMapper.toCreateCargoTypeCommand(cargoTypeRequest)
        );
    }
}
