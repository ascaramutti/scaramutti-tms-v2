package com.scaramutti.tms.workers.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * Quien puede buscar trabajadores por NUMERO DE DOCUMENTO.
 *
 * <p>El listado de trabajadores lo leen cinco roles, pero el numero de documento no viaja en
 * su respuesta. Si la busqueda mirara esa columna para todos, cualquiera de los cinco podria
 * reconstruir el documento de una persona probando prefijos: con tres digitos por consulta se
 * llega a un documento de ocho en unas decenas de intentos, y la respuesta dice que si con
 * solo devolver la fila. Por eso la columna entra en la busqueda solo para los cuatro roles
 * que mantienen el padron, que ya pueden leer ese numero en la ficha completa.
 *
 * <p>Para el encargado de almacen la busqueda sigue siendo por nombre y apellido, que es lo
 * unico que su combobox necesita. Los roles del listado NO cambian: cambia que columnas mira
 * la busqueda.
 */
@ApplicationScoped
public class WorkerDocumentSearchVisibility {

    /**
     * Lista positiva a proposito: un rol nuevo que nadie agregue aca no hereda el permiso por
     * accidente, que es el modo seguro de equivocarse. Son los mismos cuatro que leen la ficha
     * completa, y por el mismo motivo: ahi el documento ya se ve entero.
     */
    private static final Set<String> DOCUMENT_SEARCH_ROLES =
        Set.of("admin", "general_manager", "operations_manager", "finance_manager");

    @Inject CurrentUser currentUser;

    public boolean includeDocumentNumber() {
        return currentUser.hasAnyRole(DOCUMENT_SEARCH_ROLES);
    }
}
