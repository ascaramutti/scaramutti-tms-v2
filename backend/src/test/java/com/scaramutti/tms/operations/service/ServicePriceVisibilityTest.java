package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.auth.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit de la regla que decide quién ve lo que se cobra por un viaje. Es la dueña ÚNICA de esa
 * regla (la aplican el listado y el detalle, y la va a aplicar el reporte), así que se prueba
 * como función de los roles, sin levantar la aplicación.
 *
 * Lo que se fija son las dos propiedades que la hacen segura: que el veto del despacho GANE sobre
 * la lista de roles que sí ven precios, y que un rol que nadie agregó a esa lista no herede el
 * permiso. Lo que NO se prueba acá, porque el colaborador está simulado: que un token sin roles
 * diga que no, y que el rol se compare literal. Las dos son de {@code CurrentUser} y tienen sus
 * propios tests; la segunda además se verifica de punta a punta con un token real en el test de
 * integración del detalle.
 */
class ServicePriceVisibilityTest {

    /**
     * Simula un usuario con esos roles. Se simula {@link CurrentUser} en vez de construirlo con un
     * token porque acá la unidad bajo prueba es la COMPOSICIÓN de las dos listas (el veto contra
     * la lista positiva); que un token sin roles diga que no es responsabilidad de
     * {@code CurrentUser}, y tiene sus propios tests.
     */
    private static ServicePriceVisibility withRoles(Set<String> roles) {
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.hasAnyRole(any())).thenAnswer(invocation -> {
            Collection<String> asked = invocation.getArgument(0);
            return roles != null && roles.stream().anyMatch(asked::contains);
        });
        ServicePriceVisibility visibility = new ServicePriceVisibility();
        visibility.currentUser = currentUser;
        return visibility;
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "sales", "general_manager", "operations_manager"})
    void includePrices_withARoleThatSeesPrices_grants(String role) {
        assertTrue(withRoles(Set.of(role)).includePrices());
    }

    @Test
    void includePrices_asDispatcher_denies() {
        assertFalse(withRoles(Set.of("dispatcher")).includePrices());
    }

    /**
     * EL caso que justifica que la regla esté escrita como veto: con "tiene algún rol que ve
     * precios" este usuario los vería, y la regla del negocio dice exactamente lo contrario.
     */
    @Test
    void includePrices_asDispatcherWithAnotherPricedRole_stillDenies() {
        assertFalse(withRoles(Set.of("dispatcher", "sales")).includePrices());
        assertFalse(withRoles(Set.of("admin", "dispatcher")).includePrices());
    }

    /** Un rol que nadie agregó a la lista NO hereda el permiso. */
    @Test
    void includePrices_withAnUnlistedRole_denies() {
        assertFalse(withRoles(Set.of("warehouse_keeper")).includePrices());
        assertFalse(withRoles(Set.of("finance_manager")).includePrices());
    }

    /** Sin roles no se ve nada: la lista positiva es la que habilita, no el silencio. */
    @Test
    void includePrices_withoutRoles_denies() {
        assertFalse(withRoles(Set.of()).includePrices());
    }
}
