package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.model.ServiceStatus;
import com.scaramutti.tms.operations.model.ServiceStatusTransition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La maquina de estados, verificada sobre el PRODUCTO CARTESIANO completo y no sobre una muestra.
 *
 * <p>La diferencia es lo unico que importa de esta clase. Una lista de casos elegidos a mano prueba
 * lo que su autor se acordo de escribir, y un estado nuevo la atraviesa sin despeinarse: nadie
 * agrega los seis casos que faltan porque nadie sabe que faltan. Generando los pares de los enums,
 * un septimo estado convierte solo la matriz de 6x4 en 7x4 y los cuatro pares nuevos fallan hasta
 * que alguien decida a donde puede ir.
 *
 * <p>Por eso la tabla de esperados de acá abajo esta escrita APARTE de la del codigo, con las dos
 * en la misma cabeza pero no en el mismo lugar: si el test leyera la tabla de produccion, estaria
 * afirmando que la implementacion es igual a si misma.
 */
class ServiceStatusMachineTest {

    private final ServiceStatusMachine serviceStatusMachine = new ServiceStatusMachine();

    /**
     * Los destinos legales de cada estado, escritos a mano desde RN-OP1.
     *
     * <p>Incluye {@code PENDING_ASSIGNMENT → PENDING_START}, que este endpoint no expone: la
     * maquina describe el dominio, y esa transicion existe (la hace la asignacion de recursos).
     */
    private static final Map<ServiceStatus, Set<ServiceStatus>> EXPECTED_DESTINATIONS =
        new EnumMap<>(Map.of(
            ServiceStatus.PENDING_ASSIGNMENT, EnumSet.of(
                ServiceStatus.PENDING_START, ServiceStatus.CANCELLED, ServiceStatus.DELETED),
            ServiceStatus.PENDING_START, EnumSet.of(
                ServiceStatus.IN_PROGRESS, ServiceStatus.CANCELLED, ServiceStatus.DELETED),
            ServiceStatus.IN_PROGRESS, EnumSet.of(
                ServiceStatus.COMPLETED, ServiceStatus.CANCELLED),
            ServiceStatus.COMPLETED, EnumSet.noneOf(ServiceStatus.class),
            ServiceStatus.CANCELLED, EnumSet.noneOf(ServiceStatus.class),
            ServiceStatus.DELETED, EnumSet.noneOf(ServiceStatus.class)));

    /** Los 36 pares, generados de los enums: la lista crece sola cuando crece el enum. */
    static Stream<Arguments> everyPairOfStatuses() {
        return Arrays.stream(ServiceStatus.values()).flatMap(from ->
            Arrays.stream(ServiceStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("everyPairOfStatuses")
    void canTransition_overTheWholeMatrix_matchesTheBusinessRule(
            ServiceStatus from, ServiceStatus to) {
        Set<ServiceStatus> expected = EXPECTED_DESTINATIONS.get(from);
        if (expected == null) {
            throw new AssertionError("Estado nuevo sin fila en la matriz esperada: " + from
                + ". Agregala y decidi a donde puede ir, en vez de dejar que el test lo ignore.");
        }
        assertEquals(expected.contains(to), serviceStatusMachine.canTransition(from, to),
            from + " → " + to);
    }

    /**
     * Sin esta guarda, un estado nuevo sin fila caeria en la tabla de produccion y saldria por el
     * camino de "no figura", volviendose terminal sin que nadie lo haya decidido.
     */
    @Test
    void theMachine_declaresARowForEveryStatus() {
        for (ServiceStatus status : ServiceStatus.values()) {
            assertTrue(ServiceStatusMachine.destinationsOf(status) != null,
                "la maquina no declara los destinos de " + status);
        }
    }

    /** El test tambien puede quedarse viejo: si le falta una fila, avisa. */
    @Test
    void theExpectedMatrix_hasARowForEveryStatus() {
        assertEquals(EnumSet.allOf(ServiceStatus.class), EXPECTED_DESTINATIONS.keySet());
    }

    /**
     * Cada target pedible tiene que ser ALCANZABLE desde algun estado. Un target declarado al que
     * no llega nadie es una opcion en la interfaz que siempre contesta 409.
     */
    /**
     * La reapertura queda afuera a proposito: su destino sale del rastro del viaje, no de esta
     * tabla, asi que preguntarle a la maquina por ella no tiene sentido.
     */
    @ParameterizedTest
    @EnumSource(value = ServiceStatusTransition.class,
        names = { "IN_PROGRESS", "COMPLETED", "CANCELLED", "DELETED" })
    void everyRequestableTarget_isReachableFromSomeStatus(ServiceStatusTransition transition) {
        boolean reachable = Arrays.stream(ServiceStatus.values())
            .anyMatch(from -> serviceStatusMachine.canTransition(from, transition.target()));
        assertTrue(reachable, "ningun estado puede llegar a " + transition.target());
    }

    /**
     * Un estado consigo mismo NO es una transicion. La consecuencia es visible: el segundo click
     * en "Iniciar" se rechaza, en vez de contestar 200 y esconder el doble envio.
     */
    @ParameterizedTest
    @EnumSource(ServiceStatus.class)
    void selfTransitions_areNeverValid(ServiceStatus status) {
        assertFalse(serviceStatusMachine.canTransition(status, status));
    }

    @ParameterizedTest
    @EnumSource(value = ServiceStatus.class,
        names = { "COMPLETED", "CANCELLED", "DELETED" })
    void terminalStates_haveNoDestination(ServiceStatus terminal) {
        assertTrue(ServiceStatusMachine.destinationsOf(terminal).isEmpty());
    }

    /**
     * El completado es terminal para la MAQUINA pero no inmutable para la edicion: corregir los
     * datos de un viaje cerrado es legitimo. Los dos conceptos se confunden todo el tiempo y por
     * eso el caso esta escrito.
     */
    @Test
    void completed_isTerminalButNotOneOfTheTwoImmutableStates() {
        assertTrue(ServiceStatusMachine.destinationsOf(ServiceStatus.COMPLETED).isEmpty());
        // Contra el conjunto de PRODUCCION, no contra un literal del test: asi la aserción la
        // rompe meter el completado entre los inmutables, que es lo que quiere impedir.
        assertFalse(ServiceStatusGuards.IMMUTABLE_STATUSES.contains(ServiceStatus.COMPLETED));
    }

    /** Eliminar solo desde los dos pendientes: lo que ya salio a ruta ocurrio, y se cancela. */
    @Test
    void deleted_isReachableOnlyFromTheTwoPendingStates() {
        assertTrue(serviceStatusMachine.canTransition(
            ServiceStatus.PENDING_ASSIGNMENT, ServiceStatus.DELETED));
        assertTrue(serviceStatusMachine.canTransition(
            ServiceStatus.PENDING_START, ServiceStatus.DELETED));
        assertFalse(serviceStatusMachine.canTransition(
            ServiceStatus.IN_PROGRESS, ServiceStatus.DELETED));
    }

    /**
     * Iniciar exige haber pasado por la asignacion. Suena a que deberia andar desde cualquier
     * pendiente y no: sin conductor ni unidad no hay viaje que arranque.
     */
    @Test
    void inProgress_isNotReachableFromPendingAssignment() {
        assertFalse(serviceStatusMachine.canTransition(
            ServiceStatus.PENDING_ASSIGNMENT, ServiceStatus.IN_PROGRESS));
    }

    /**
     * Los dos conjuntos que hoy dicen lo mismo por dos caminos distintos: "no tiene a donde ir"
     * (esta maquina, que describe el DOMINIO) y "no se puede volver ahi" (la guarda de la
     * reapertura). Estan escritos aparte a proposito —agregarle un arco a la maquina es un cambio
     * de bajo ceremonial, y una guarda derivada empezaria a aceptar ese destino sin que nadie lo
     * decida—, asi que hace falta un caso que avise el dia que dejen de coincidir.
     */
    @Test
    void theNonRestorableStatuses_matchTheOnesWithoutDestinations() {
        Set<ServiceStatus> sinSalida = Arrays.stream(ServiceStatus.values())
            .filter(status -> ServiceStatusMachine.destinationsOf(status).isEmpty())
            .collect(java.util.stream.Collectors.toSet());

        assertEquals(sinSalida, ServiceStatusGuards.NON_RESTORABLE_STATUSES,
            "los dos conjuntos dejaron de coincidir: hay que decidir si la reapertura acepta ese "
                + "destino, no dejar que lo decida la tabla de arcos");
    }

    /** Y el conjunto que devuelve la maquina no se puede modificar desde afuera. */
    @Test
    void destinationsOf_returnsAFrozenSet() {
        Set<ServiceStatus> destinos = ServiceStatusMachine.destinationsOf(ServiceStatus.CANCELLED);

        assertThrows(UnsupportedOperationException.class,
            () -> destinos.add(ServiceStatus.IN_PROGRESS),
            "abrir un arco desde afuera cambiaria la regla para toda la JVM");
    }
}