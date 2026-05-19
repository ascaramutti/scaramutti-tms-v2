package com.scaramutti.tms.cargotypes.api;

import com.scaramutti.tms.cargotypes.dto.CargoTypeResponse;
import com.scaramutti.tms.cargotypes.mapper.CargoTypeResourceMapper;
import com.scaramutti.tms.cargotypes.service.CargoTypeService;
import com.scaramutti.tms.shared.dto.PageResponse;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

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
}
