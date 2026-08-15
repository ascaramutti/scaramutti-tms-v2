package com.scaramutti.tms.operations.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Como se NOMBRA cada estado del viaje cuando lo lee una persona.
 *
 * <p>Vive aparte porque ya son dos los lugares que lo necesitan y los dos son texto que sale de la
 * aplicacion: el mensaje del conflicto de recursos ("ya esta asignado al servicio SRV-0042 (en
 * ruta)") y, desde este PR, la bitacora de la transicion y el detalle del rechazo por transicion
 * invalida. Si cada uno tuviera su copia, el mismo estado terminaria con dos nombres distintos
 * segun por que pantalla se lo mire.
 *
 * <p>Estan los SEIS, incluidos los que hoy ningun mensaje alcanza. El mapa se valida completo al
 * cargar la clase: un estado nuevo sin etiqueta revienta al arrancar en vez de aparecer en una
 * pantalla con su nombre tecnico en ingles y mayusculas.
 */
public final class ServiceStatusLabels {

    private static final Map<ServiceStatus, String> LABELS;

    static {
        Map<ServiceStatus, String> labels = new EnumMap<>(ServiceStatus.class);
        labels.put(ServiceStatus.PENDING_ASSIGNMENT, "pendiente de asignación");
        labels.put(ServiceStatus.PENDING_START, "pendiente de inicio");
        labels.put(ServiceStatus.IN_PROGRESS, "en ruta");
        labels.put(ServiceStatus.COMPLETED, "completado");
        labels.put(ServiceStatus.CANCELLED, "cancelado");
        labels.put(ServiceStatus.DELETED, "eliminado");

        Set<ServiceStatus> missing = EnumSet.allOf(ServiceStatus.class);
        missing.removeAll(labels.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Estados sin etiqueta en es-PE: " + missing);
        }
        LABELS = Map.copyOf(labels);
    }

    private ServiceStatusLabels() {}

    /** La etiqueta en es-PE, en minuscula: se usa dentro de una frase, no como titulo. */
    public static String of(ServiceStatus status) {
        return LABELS.get(status);
    }
}
