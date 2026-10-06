package com.scaramutti.tms.workers.api;

import com.scaramutti.tms.workers.dto.DocumentTypeResponse;
import com.scaramutti.tms.workers.service.DocumentTypeService;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Tipos de documento de identidad (GET /document-types).
 *
 * <p>Ruta PLANA y no bajo la del trabajador: es un catalogo de {@code public}, no una
 * parte de un trabajador, y colgarlo de la ruta del padron competiria con la del detalle.
 *
 * <p>Lo leen los cuatro roles que mantienen el padron, no todos los autenticados: el
 * catalogo solo existe para armar el formulario de un trabajador.
 */
@Authenticated
@Path("/document-types")
@Produces(MediaType.APPLICATION_JSON)
public class DocumentTypeResource {

    @Inject DocumentTypeService documentTypeService;

    @GET
    @RolesAllowed({"admin", "general_manager", "operations_manager", "finance_manager"})
    public List<DocumentTypeResponse> listDocumentTypes() {
        return documentTypeService.listDocumentTypes();
    }
}
