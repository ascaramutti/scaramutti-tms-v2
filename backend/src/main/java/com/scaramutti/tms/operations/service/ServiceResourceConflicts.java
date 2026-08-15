package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.OperationsError;
import com.scaramutti.tms.operations.dto.ServiceResourceConflictResponse;
import com.scaramutti.tms.operations.model.ServiceResourceKind;
import com.scaramutti.tms.operations.model.ServiceStatusLabels;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.repository.ServiceResourceConflictRepository;
import com.scaramutti.tms.shared.repository.ServiceResourceConflictRepository.ServiceResourceConflictRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qué recursos de un viaje ya retiene OTRO viaje activo, y cómo se lo cuenta al que preguntó.
 *
 * <p>Existe porque son DOS las operaciones que pueden poner a dos viajes a compartir un conductor:
 * asignar recursos, que es la obvia, y REABRIR uno cancelado, que es la que no se ve. Cancelar no
 * limpia los recursos —se conservan a propósito, para no perder quién estaba asignado— pero un
 * viaje cancelado deja de retenerlos, así que en el medio otro viaje se los puede llevar. Devolver
 * el primero a un estado que retiene, sin volver a mirar, los pone a compartirlo <b>sin que nadie
 * lo haya decidido y sin la línea de bitácora que dice que se forzó</b>, que es exactamente lo que
 * este módulo existe para impedir.
 *
 * <p>Con una copia por servicio, el mensaje del conflicto se separa: al primero que alguien
 * retoque, la misma situación se explica de dos formas distintas según por qué puerta se entró.
 */
@ApplicationScoped
public class ServiceResourceConflicts {

    /** Los dos miembros de extensión que la interfaz necesita para ofrecer "Forzar". */
    private static final String FORCIBLE_FIELD = "forcible";
    private static final String CONFLICTS_FIELD = "conflicts";

    @Inject ServiceResourceConflictRepository serviceResourceConflictRepository;

    /**
     * Los recursos indicados que ya retiene otro viaje activo, con su etiqueta pegada.
     *
     * <p>El nombre no sale de la consulta: los conflictos solo pueden ser sobre los tres recursos
     * que se pasaron, y esos ya están resueltos. Traerlo de la consulta obligaría a colgarle tres
     * uniones externas para volver a leer lo mismo.
     *
     * <p>La consulta toma ella misma el lock de los recursos que mira, así que quien la llame
     * tiene que tener ya el lock de la fila del viaje: ese es el que puso el tope de espera, y al
     * revés la espera por el lock de un recurso no tendría techo.
     */
    public List<ServiceResourceConflictResponse> find(long serviceId,
            Integer driverId, Integer tractorId, Integer trailerId,
            String driverName, String tractorPlate, String trailerPlate) {
        Map<ServiceResourceKind, String> namesByKind =
            namesByKind(driverName, tractorPlate, trailerPlate);

        List<ServiceResourceConflictRow> rows = serviceResourceConflictRepository.findActiveConflicts(
            serviceId, driverId, tractorId, trailerId);

        List<ServiceResourceConflictResponse> conflicts = new ArrayList<>();
        for (ServiceResourceConflictRow row : rows) {
            // Se aplastan ACA, al armar el DTO, y no solo en el texto del error: los dos valores
            // salen de la base y las dos salidas de la misma respuesta —el mensaje y la lista— no
            // pueden discrepar sobre si el salto de linea se ve. La pantalla que junte la lista en
            // un bloque de texto tendria el mismo agujero que la bitacora cierra.
            conflicts.add(new ServiceResourceConflictResponse(
                // display() y no flattenLineBreaks(): ademas de aplastar, NOMBRA el vacio. El
                // nombre sale de un JOIN contra los trabajadores del sistema anterior y puede venir
                // nulo si esa fila no esta; sin esto la bitacora guarda "el conductor null" en un
                // rastro que despues nadie puede corregir.
                row.resource(), ServiceLogText.display(namesByKind.get(row.resource())),
                ServiceLogText.display(row.serviceCode()), row.serviceStatus()));
        }
        return conflicts;
    }

    /**
     * Como se llama cada recurso, por tipo. Vive aca y no inline en cada mensaje porque es
     * vocabulario del modulo, no de un mensaje: con una copia por lado, el dia que aparezca un
     * cuarto tipo de recurso se actualiza una sola y el otro nombra "(vacio)" donde va un nombre.
     */
    public Map<ServiceResourceKind, String> namesByKind(
            String driverName, String tractorPlate, String trailerPlate) {
        Map<ServiceResourceKind, String> namesByKind = new LinkedHashMap<>();
        namesByKind.put(ServiceResourceKind.DRIVER, driverName);
        namesByKind.put(ServiceResourceKind.TRACTOR, tractorPlate);
        namesByKind.put(ServiceResourceKind.TRAILER, trailerPlate);
        return namesByKind;
    }

    /**
     * El 409 forzable, con los dos miembros de extension que la interfaz necesita. El texto nombra
     * al primero y cuenta el resto: el detalle completo ya viaja en la lista, y un mensaje que
     * enumere cinco recursos no se lee.
     */
    public RuntimeException asForcibleConflict(List<ServiceResourceConflictResponse> conflicts) {
        return OperationsError.RESOURCE_CONFLICT.toException(
            detailOf(conflicts),
            Map.of(FORCIBLE_FIELD, true, CONFLICTS_FIELD, conflicts));
    }

    /**
     * Como se NOMBRA el conflicto en la bitacora cuando se decide forzarlo.
     *
     * <p>Vuelve a aplastar los saltos aunque {@link #find} ya lo haga: es un metodo publico sobre
     * un record publico que cualquiera puede construir, y la linea termina en la bitacora, que es
     * una linea por dato. La fuente realista de un salto es el nombre del conductor, que viene de
     * los catalogos compartidos con el sistema anterior. Aplastar dos veces no cuesta nada;
     * confiar en que el que llama ya lo hizo cuesta el agujero entero.
     */
    public String forcedLine(ServiceResourceConflictResponse conflict) {
        return label(conflict.resource()).toLowerCase(java.util.Locale.ROOT)
            + " " + ServiceLogText.display(conflict.resourceName())
            + " ya estaba " + assignedParticiple(conflict.resource()) + " al servicio "
            + ServiceLogText.display(conflict.serviceCode())
            + " (" + ServiceStatusLabels.of(conflict.serviceStatus()) + ")";
    }

    private String detailOf(List<ServiceResourceConflictResponse> conflicts) {
        ServiceResourceConflictResponse first = conflicts.get(0);
        // Los valores ya vienen aplastados desde find: aca solo se arma la frase.
        String detail = label(first.resource()) + " " + first.resourceName()
            + " ya está " + assignedParticiple(first.resource()) + " al servicio "
            + first.serviceCode() + " (" + ServiceStatusLabels.of(first.serviceStatus()) + ").";
        // Se cuentan RECURSOS distintos, no filas: el mismo conductor retenido por dos viajes son
        // dos filas y UN solo recurso en conflicto, y decir "hay 1 recurso mas" ahi seria falso.
        long others = conflicts.stream()
            .map(ServiceResourceConflictResponse::resource).distinct().count() - 1;
        if (others > 0) {
            detail += others == 1
                ? " Hay 1 recurso más en conflicto."
                : " Hay " + others + " recursos más en conflicto.";
        }
        return detail;
    }

    /**
     * El 400 de "no existe o esta dado de baja", con el mensaje concordado por genero.
     *
     * <p>Vive aca y no en el service que lo estreno porque la regla es del MODULO y no de un
     * endpoint: la aplica todo el que ELIJA recursos. Con una copia por endpoint, endurecer el
     * chequeo por un lado (sumarle la disponibilidad del catalogo, cambiar la baja por un estado)
     * deja al otro aceptando lo que este rechaza, y el mismo error deja de leerse igual segun por
     * donde entre.
     *
     * <p>Recibe el TIPO y no una etiqueta ya armada, por lo mismo que el mensaje del conflicto: con
     * la etiqueta suelta, "La carreta indicado no existe o esta inactivo" se cuela sin que nada
     * falle.
     */
    public void requireActiveResource(boolean usable, ServiceResourceKind kind) {
        if (!usable) {
            throw CommonError.VALIDATION_FAILED.toException(kind == ServiceResourceKind.TRAILER
                ? label(kind) + " indicada no existe o está inactiva"
                : label(kind) + " indicado no existe o está inactivo");
        }
    }

    /**
     * Como se nombra el recurso al usuario. Publico porque lo usa tambien el 400 de "no existe o
     * esta inactivo": la concordancia de genero tiene que decidirse en UN lugar, o el mismo tipo
     * de recurso termina con dos nombres segun que mensaje lo cuente.
     */
    public String label(ServiceResourceKind kind) {
        return switch (kind) {
            case DRIVER -> "El conductor";
            case TRACTOR -> "El tracto";
            case TRAILER -> "La carreta";
        };
    }

    /**
     * El participio concuerda con el genero del recurso. Parece un detalle de estilo y no lo es:
     * el mensaje lo lee la persona que decide si pisa un control de disponibilidad, y "La carreta
     * ABC123 ya está asignado" delata que el texto se arma pegando pedazos, que es justo lo que
     * hace dudar de si el dato tambien esta armado a los pedazos.
     */
    private String assignedParticiple(ServiceResourceKind kind) {
        return kind == ServiceResourceKind.TRAILER ? "asignada" : "asignado";
    }

}
