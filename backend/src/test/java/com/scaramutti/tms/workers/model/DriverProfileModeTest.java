package com.scaramutti.tms.workers.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * La modalidad de ficha viaja en la base como texto y su dominio lo cierra la restriccion de
 * la columna. Lo que fija esta prueba es que traducirla NO perdone.
 */
class DriverProfileModeTest {

    /**
     * Contra literales escritos aca y NO contra {@code valueOf}, que es la mitad de lo que
     * hace la funcion bajo prueba: derivarlo de la misma fuente reduce el caso a comparar la
     * funcion consigo misma.
     */
    @Test
    void theThreeValuesOfTheColumn_resolve() {
        assertEquals(DriverProfileMode.REQUIRED, DriverProfileMode.fromColumn("REQUIRED"));
        assertEquals(DriverProfileMode.OPTIONAL, DriverProfileMode.fromColumn("OPTIONAL"));
        assertEquals(DriverProfileMode.NONE, DriverProfileMode.fromColumn("NONE"));
    }

    /**
     * El texto se normaliza antes de traducirlo. La otra mitad de la funcion: con los tres
     * valores del dominio, que ya vienen en mayusculas y sin espacios, borrar el recorte o el
     * pase a mayusculas no rompe nada, asi que la intencion quedaba sin fijar.
     */
    @ParameterizedTest
    @ValueSource(strings = {" REQUIRED ", "required", "Required"})
    void theColumnText_isNormalizedBeforeTranslating(String value) {
        assertEquals(DriverProfileMode.REQUIRED, DriverProfileMode.fromColumn(value));
    }

    /**
     * Un valor fuera del dominio REVIENTA y no vuelve nulo.
     *
     * <p>Servir una modalidad vacia haria que el formulario decida mal si pedir la ficha de
     * conductor, y eso se descubre recien cuando alguien no puede dar de alta a un conductor.
     * Llegar aca con otra cosa significa que alguien toco la columna por fuera de una
     * migracion, y eso tiene que gritar.
     */
    @ParameterizedTest
    @ValueSource(strings = {"MAYBE", "otra cosa", ""})
    void aValueOutsideTheDomain_blowsUpInsteadOfReturningNull(String value) {
        assertThrows(IllegalStateException.class, () -> DriverProfileMode.fromColumn(value));
    }

    @Test
    void aNullValue_alsoBlowsUp() {
        assertThrows(IllegalStateException.class, () -> DriverProfileMode.fromColumn(null));
    }
}
