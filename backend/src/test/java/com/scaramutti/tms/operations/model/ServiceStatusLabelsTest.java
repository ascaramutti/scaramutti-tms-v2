package com.scaramutti.tms.operations.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Las etiquetas en es-PE de los estados, que salen al usuario por tres puertas: el mensaje del
 * conflicto de recursos, el rechazo por transicion invalida y la bitacora de la transicion.
 *
 * <p>El caso que sostiene todo es el parametrizado: recorre el enum, asi que un estado nuevo sin
 * etiqueta no se cuela. La alternativa —comparar contra una lista escrita a mano— falla justo en
 * el caso que importa, porque quien agrega el estado tampoco agrega la fila del test.
 */
class ServiceStatusLabelsTest {

    @ParameterizedTest
    @EnumSource(ServiceStatus.class)
    void everyStatus_hasASpanishLabel(ServiceStatus status) {
        String label = ServiceStatusLabels.of(status);
        assertNotNull(label, status + " no tiene etiqueta en es-PE");
        assertFalse(label.isBlank());
        assertFalse(label.equals(status.name()),
            status + " esta usando su nombre tecnico como etiqueta");
    }

    /** Dos estados con el mismo nombre visible harian ilegible el mensaje "de X a Y". */
    @Test
    void theLabels_areUnique() {
        Set<String> labels = new HashSet<>();
        for (ServiceStatus status : ServiceStatus.values()) {
            assertTrue(labels.add(ServiceStatusLabels.of(status)),
                "etiqueta repetida: " + ServiceStatusLabels.of(status));
        }
        assertEquals(ServiceStatus.values().length, labels.size());
    }

    /**
     * Van en MINUSCULA porque se usan dentro de una frase ("No se puede pasar de \"completado\" a
     * \"en ruta\""), no como titulo de columna. Una mayuscula inicial se lee como si el sistema
     * estuviera nombrando otra cosa.
     */
    @ParameterizedTest
    @EnumSource(ServiceStatus.class)
    void theLabels_startInLowerCase(ServiceStatus status) {
        String label = ServiceStatusLabels.of(status);
        assertEquals(label.toLowerCase(java.util.Locale.ROOT).charAt(0), label.charAt(0),
            "la etiqueta de " + status + " empieza en mayuscula: " + label);
    }

    /** Los textos exactos, para que un retoque cosmetico sea una decision y no un descuido. */
    @Test
    void theLabels_readAsTheBusinessNamesThem() {
        assertEquals("pendiente de asignación",
            ServiceStatusLabels.of(ServiceStatus.PENDING_ASSIGNMENT));
        assertEquals("pendiente de inicio", ServiceStatusLabels.of(ServiceStatus.PENDING_START));
        assertEquals("en ruta", ServiceStatusLabels.of(ServiceStatus.IN_PROGRESS));
        assertEquals("completado", ServiceStatusLabels.of(ServiceStatus.COMPLETED));
        assertEquals("cancelado", ServiceStatusLabels.of(ServiceStatus.CANCELLED));
        assertEquals("eliminado", ServiceStatusLabels.of(ServiceStatus.DELETED));
    }

    /**
     * Cancelado y eliminado tienen que sonar DISTINTO: son cosas distintas (un viaje que se aborto
     * contra un registro que nunca debio existir) y el usuario decide entre las dos leyendo esto.
     */
    @Test
    void cancelledAndDeleted_doNotSoundLikeTheSameThing() {
        assertFalse(ServiceStatusLabels.of(ServiceStatus.CANCELLED)
            .equals(ServiceStatusLabels.of(ServiceStatus.DELETED)));
    }
}
