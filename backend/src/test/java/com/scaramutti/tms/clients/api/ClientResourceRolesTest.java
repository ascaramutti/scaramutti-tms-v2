package com.scaramutti.tms.clients.api;

import com.scaramutti.tms.operations.api.ServiceResource;
import com.scaramutti.tms.quotations.api.QuotationResource;
import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La relacion entre la lectura de clientes y las pantallas que buscan clientes, por reflexion. El
 * combobox de cliente de cotizaciones, de viajes y de clientes llama al listado: un rol sumado a
 * una de esas escrituras sin sumarlo a la lectura deja su combobox en 403, y los tests que
 * enumeran roles no lo ven, porque cada uno afirma su lista y no la relacion entre las dos.
 */
class ClientResourceRolesTest {

    @Test
    void everyRoleThatPicksAClientOnAScreen_canListClients() {
        Set<String> pickers = new HashSet<>();
        pickers.addAll(rolesOf(QuotationResource.class, "createQuotation"));
        pickers.addAll(rolesOf(QuotationResource.class, "updateQuotation"));
        pickers.addAll(rolesOf(ServiceResource.class, "createService"));
        pickers.addAll(rolesOf(ServiceResource.class, "updateService"));
        pickers.addAll(rolesOf(ClientResource.class, "createClient"));
        pickers.addAll(rolesOf(ClientResource.class, "updateClient"));
        Set<String> readers = rolesOf(ClientResource.class, "listClients");

        assertTrue(readers.containsAll(pickers),
            "su combobox de cliente responderia 403; faltan en el listado: "
                + pickers.stream().filter(role -> !readers.contains(role)).collect(Collectors.toSet()));
    }

    /** La ficha y la edicion leen el cliente por id con los mismos datos que el listado. */
    @Test
    void theDetailHasTheSameRolesAsTheList() {
        assertEquals(rolesOf(ClientResource.class, "listClients"),
            rolesOf(ClientResource.class, "getClient"),
            "el listado y la ficha exponen los mismos datos; si divergen, que sea una decision escrita");
    }

    private static Set<String> rolesOf(Class<?> resourceClass, String methodName) {
        Method method = Arrays.stream(resourceClass.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no existe el metodo " + methodName + " en " + resourceClass.getSimpleName()));
        RolesAllowed rolesAllowed = method.getAnnotation(RolesAllowed.class);
        if (rolesAllowed == null) {
            throw new AssertionError(methodName + " no declara @RolesAllowed");
        }
        return Set.of(rolesAllowed.value());
    }
}
