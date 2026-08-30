package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.model.ServiceStatus;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * La maquina de estados del viaje (RN-OP1): desde cada estado, a cuales se puede ir.
 *
 * <p>Es la tabla de ARCOS, y describe el dominio entero, no lo que este endpoint expone. Por eso
 * incluye {@code PENDING_ASSIGNMENT -> PENDING_START}, que nadie pide como transicion: la hace la
 * asignacion de recursos, como EFECTO. Sacarla dejaria la tabla diciendo que ese paso no existe,
 * que es falso, y la volveria un reflejo del endpoint en vez del modelo.
 *
 * <p>Las seis claves estan escritas de forma EXPLICITA, incluidos los tres terminales con el
 * conjunto vacio, y hay una guarda de completitud que revienta al arrancar si falta alguna. Sin
 * eso, un estado nuevo del enum caeria en el "no figura, entonces no sale a ningun lado" y se
 * volveria terminal en silencio: la clase de error que ningun test encuentra porque el test
 * tambien se escribe mirando la lista incompleta.
 */
@ApplicationScoped
public class ServiceStatusMachine {

    private static final Map<ServiceStatus, Set<ServiceStatus>> TRANSITIONS;

    static {
        Map<ServiceStatus, Set<ServiceStatus>> transitions = new EnumMap<>(ServiceStatus.class);

        // Sin recursos: se le pueden asignar (lo hace el endpoint de asignacion, no este), o
        // sacarlo del circuito por cualquiera de las dos puertas.
        transitions.put(ServiceStatus.PENDING_ASSIGNMENT, EnumSet.of(
            ServiceStatus.PENDING_START, ServiceStatus.CANCELLED, ServiceStatus.DELETED));

        // Con recursos y sin salir: puede arrancar, o morir por cualquiera de las dos puertas.
        transitions.put(ServiceStatus.PENDING_START, EnumSet.of(
            ServiceStatus.IN_PROGRESS, ServiceStatus.CANCELLED, ServiceStatus.DELETED));

        // Ya en ruta: termina o se cancela. ELIMINAR no esta, y no es un olvido: eliminar etiqueta
        // el registro que nunca debio existir, y un viaje que ya salio ocurrio de verdad. Lo que
        // ocurrio se cancela; lo que nunca fue se elimina.
        transitions.put(ServiceStatus.IN_PROGRESS, EnumSet.of(
            ServiceStatus.COMPLETED, ServiceStatus.CANCELLED));

        // Los tres finales del ciclo. El completado se sigue EDITANDO (corregir los datos de un
        // viaje cerrado es legitimo), pero no se mueve de estado: no hay a donde.
        transitions.put(ServiceStatus.COMPLETED, EnumSet.noneOf(ServiceStatus.class));
        transitions.put(ServiceStatus.CANCELLED, EnumSet.noneOf(ServiceStatus.class));
        transitions.put(ServiceStatus.DELETED, EnumSet.noneOf(ServiceStatus.class));

        requireEveryStatusDeclared(transitions);
        TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    /**
     * Un estado sin fila declarada seria terminal sin que nadie lo haya decidido. Se revienta al
     * cargar la clase, que es lo mas temprano posible: el arranque de la aplicacion falla en vez
     * de servir una regla que nadie escribio.
     */
    private static void requireEveryStatusDeclared(Map<ServiceStatus, Set<ServiceStatus>> transitions) {
        Set<ServiceStatus> missing = EnumSet.allOf(ServiceStatus.class);
        missing.removeAll(transitions.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "La maquina de estados no declara los destinos de: " + missing);
        }
    }

    /** Si el par existe en la tabla. Un estado consigo mismo nunca esta: no es una transicion. */
    public boolean canTransition(ServiceStatus from, ServiceStatus to) {
        return TRANSITIONS.get(from).contains(to);
    }

    /** Los destinos declarados de un estado. Visible para el test que recorre la matriz completa. */
    static Set<ServiceStatus> destinationsOf(ServiceStatus from) {
        // Congelado tambien por dentro. `unmodifiableMap` protege el mapa pero NO sus valores, y
        // estos son EnumSet mutables: cualquier clase del paquete podria abrirle un arco a un
        // terminal para toda la JVM, sin que la guarda de arranque ni ningun test lo vean.
        return Collections.unmodifiableSet(TRANSITIONS.get(from));
    }
}
