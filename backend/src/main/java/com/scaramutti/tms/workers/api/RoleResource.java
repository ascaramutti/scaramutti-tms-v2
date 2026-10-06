package com.scaramutti.tms.workers.api;

import com.scaramutti.tms.workers.dto.RoleResponse;
import com.scaramutti.tms.workers.service.RoleService;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Los roles del sistema (GET /roles), que desde el mantenimiento de trabajadores son
 * tambien los cargos.
 *
 * <p>Vive en este modulo porque es su primer consumidor y el unico que hoy escribe el rol
 * de un trabajador; cuando exista el modulo de usuarios se decide si se muda.
 *
 * <p>Devuelve la tabla entera y no una vista del modulo: el que viene despues la reutiliza
 * quedandose con los roles que pueden tener usuario.
 */
@Authenticated
@Path("/roles")
@Produces(MediaType.APPLICATION_JSON)
public class RoleResource {

    @Inject RoleService roleService;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager"})
    public List<RoleResponse> listRoles() {
        return roleService.listRoles();
    }
}
