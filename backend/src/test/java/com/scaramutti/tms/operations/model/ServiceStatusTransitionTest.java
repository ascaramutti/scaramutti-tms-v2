package com.scaramutti.tms.operations.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La tabla de politica por target: que exige cada transicion que un usuario puede pedir.
 *
 * <p>Los casos parametrizados recorren el enum, no una lista: una constante nueva entra sola a los
 * invariantes de abajo. Los casos con nombre propio fijan las decisiones de negocio, que son las
 * que hay que poder discutir mirando un test y no rastreando ifs por el servicio.
 */
class ServiceStatusTransitionTest {

    /** Los cuatro destinos con estado fijo son un subconjunto propio de los seis del viaje. */
    @Test
    void theRequestableTargets_areASubsetOfTheServiceStatuses() {
        Set<ServiceStatus> targets = Arrays.stream(ServiceStatusTransition.values())
            .map(ServiceStatusTransition::target).filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(ServiceStatus.class)));

        assertEquals(EnumSet.of(ServiceStatus.IN_PROGRESS, ServiceStatus.COMPLETED,
            ServiceStatus.CANCELLED, ServiceStatus.DELETED), targets,
            "la reapertura no aporta destino fijo: lo saca del rastro");
        assertFalse(targets.contains(ServiceStatus.PENDING_ASSIGNMENT));
        // El paso a "pendiente de inicio" no se pide: es efecto de asignar recursos.
        assertFalse(targets.contains(ServiceStatus.PENDING_START));
    }

    /** Ningun campo de la tabla puede quedar sin contestar, ni siquiera con un null silencioso. */
    @ParameterizedTest
    @EnumSource(ServiceStatusTransition.class)
    void everyTransition_answersEveryQuestionOfTheTable(ServiceStatusTransition transition) {
        // El destino es la UNICA respuesta que puede faltar, y solo en la que lo resuelve del
        // rastro. Quien la lea tiene que preguntar antes; por eso las dos cosas se afirman juntas.
        assertEquals(transition.target() == null, transition.restoresPreviousStatus(),
            transition + ": un destino nulo solo se admite si lo restaura del historial");
        assertNotNull(transition.dateColumn());
        assertNotNull(transition.noteLabel());
        assertNotNull(transition.vetoedRoles());
        assertFalse(transition.noteLabel().isBlank());
    }

    /**
     * Las TRES que exigen version son EXACTAMENTE las tres que exigen motivo, y las tres que
     * llevan veto: las dos que sacan el viaje del circuito y la que las deshace. No es casualidad
     * ni acoplamiento: las tres preguntas describen el mismo hecho —que la operacion mueve el
     * viaje entero de lugar y no se toma a la ligera—, y si algun dia se separan, este test obliga
     * a decir por que.
     */
    @Test
    void theDestructiveTransitions_areTheOnesThatDemandVersionReasonAndAVeto() {
        Set<ServiceStatusTransition> requireIfMatch = filter(ServiceStatusTransition::requiresIfMatch);
        Set<ServiceStatusTransition> requireNote = filter(ServiceStatusTransition::requiresNote);
        Set<ServiceStatusTransition> vetoed = filter(t -> !t.vetoedRoles().isEmpty());

        // La reapertura entra en el mismo grupo: deshace una decision irreversible, asi que pide
        // exactamente lo mismo que las dos que deshace. Que comparta los tres requisitos NO es
        // acoplamiento: las tres describen el mismo hecho, que la operacion no se toma a la ligera.
        Set<ServiceStatusTransition> irreversible = EnumSet.of(ServiceStatusTransition.CANCELLED,
            ServiceStatusTransition.DELETED, ServiceStatusTransition.REOPENED);
        assertEquals(irreversible, requireIfMatch);
        assertEquals(irreversible, requireNote);
        assertEquals(irreversible, vetoed);
    }

    /**
     * La reapertura es la mas acotada de la tabla: solo administracion y gerencia general. Escrito
     * como veto —los otros dos roles del endpoint— para que un token con varios roles no la esquive
     * por el que si puede, igual que el resto de la tabla.
     */
    @Test
    void reopening_isVetoedForEveryoneBelowGeneralManagement() {
        assertEquals(Set.of("operations_manager", "dispatcher"),
            ServiceStatusTransition.REOPENED.vetoedRoles());
        assertTrue(ServiceStatusTransition.REOPENED.restoresPreviousStatus());
        assertEquals(ServiceStatusTransition.DateColumn.NONE,
            ServiceStatusTransition.REOPENED.dateColumn(),
            "reabrir no fecha nada: devuelve el viaje a donde estaba, no lo mueve en el tiempo");
    }

    /**
     * La regla de la reapertura es POSITIVA ("solo administracion y gerencia general") pero esta
     * escrita como negativa, y las dos solo coinciden por aritmetica: el resultado depende de que
     * los roles operativos sean exactamente cuatro. Un quinto —un asistente de operaciones, el
     * finance_manager que quedo pendiente— entraria a la anotacion del endpoint y a la lista de
     * roles operativos, que es el par de ediciones natural, y HEREDARIA la reapertura sin que
     * nadie toque su linea.
     *
     * <p>El propio modulo documenta ese default como el equivocado, en la visibilidad de precios:
     * "un rol nuevo que nadie agregue aca no hereda el permiso por accidente, que es el modo
     * seguro de equivocarse". Este caso hace explicita la resta para que el dia que crezca la
     * lista, alguien tenga que decidir si el rol nuevo reabre.
     */
    @Test
    void reopening_endsUpAllowedForExactlyAdminAndGeneralManagement() {
        Set<String> allowed = new java.util.HashSet<>(
            ServiceStatusChangeAuthorizationRoles.OPERATING_ROLES);
        allowed.removeAll(ServiceStatusTransition.REOPENED.vetoedRoles());

        assertEquals(Set.of("admin", "general_manager"), allowed,
            "creció la lista de roles operativos y alguien heredó la reapertura sin decidirlo");
    }

    /** Y es la unica: las otras cuatro tienen su destino escrito en la tabla. */
    @ParameterizedTest
    @EnumSource(value = ServiceStatusTransition.class,
        names = { "IN_PROGRESS", "COMPLETED", "CANCELLED", "DELETED" })
    void everyOtherTransition_declaresItsTarget(ServiceStatusTransition transition) {
        assertNotNull(transition.target());
        assertFalse(transition.restoresPreviousStatus());
    }

    /**
     * Cada transicion fecha lo que le toca, y solo eso. Iniciar escribe el inicio, finalizar el
     * fin, y las TRES restantes no fechan el viaje: fechan la decision, que ya queda en la
     * bitacora con su propia marca.
     */
    @Test
    void eachTransition_writesTheDateColumnThatBelongsToIt() {
        assertEquals(ServiceStatusTransition.DateColumn.START,
            ServiceStatusTransition.IN_PROGRESS.dateColumn());
        assertEquals(ServiceStatusTransition.DateColumn.END,
            ServiceStatusTransition.COMPLETED.dateColumn());
        assertEquals(ServiceStatusTransition.DateColumn.NONE,
            ServiceStatusTransition.CANCELLED.dateColumn());
        assertEquals(ServiceStatusTransition.DateColumn.NONE,
            ServiceStatusTransition.DELETED.dateColumn());
    }

    /**
     * El texto libre se TITULA distinto segun lo que significa. En una transicion destructiva el
     * texto ES el motivo por el que el viaje no existe, y llamarlo "Nota" en el unico rastro que
     * queda le baja el peso a lo que despues hay que rendir.
     */
    @Test
    void theFreeTextIsCalledReason_onlyWhereItIsTheReason() {
        assertEquals("Nota", ServiceStatusTransition.IN_PROGRESS.noteLabel());
        assertEquals("Nota", ServiceStatusTransition.COMPLETED.noteLabel());
        assertEquals("Motivo", ServiceStatusTransition.CANCELLED.noteLabel());
        assertEquals("Motivo", ServiceStatusTransition.DELETED.noteLabel());
        assertEquals(ServiceStatusTransition.DateColumn.NONE,
            ServiceStatusTransition.REOPENED.dateColumn(),
            "reabrir no fecha el viaje: el nombre del caso dice CADA transicion, y son cinco");
    }

    /**
     * Al despacho se lo veta en las dos que sacan el viaje del circuito Y en la que las deshace:
     * opera el viaje, no decide matarlo ni resucitarlo. Que iniciar y finalizar NO tengan veto es
     * la otra mitad de la regla, y sin este caso una implementacion que vetara al despacho en las
     * cinco pasaria igual.
     *
     * <p>La reapertura veta ADEMAS a la gerencia de operaciones, asi que su lista no es igual a la
     * de las otras dos: eso lo fija el caso de los roles que SI pueden pedirla.
     */
    @Test
    void dispatcherIsVetoed_onlyOnTheDestructiveTransitions() {
        assertEquals(Set.of("dispatcher"), ServiceStatusTransition.CANCELLED.vetoedRoles());
        assertEquals(Set.of("dispatcher"), ServiceStatusTransition.DELETED.vetoedRoles());
        assertTrue(ServiceStatusTransition.REOPENED.vetoedRoles().contains("dispatcher"),
            "al despacho tambien se lo veta de deshacer, no solo de matar el viaje");
        assertTrue(ServiceStatusTransition.IN_PROGRESS.vetoedRoles().isEmpty());
        assertTrue(ServiceStatusTransition.COMPLETED.vetoedRoles().isEmpty());
    }

    /** La lista de vetados no se puede modificar desde afuera: es politica, no un buffer. */
    @ParameterizedTest
    @EnumSource(ServiceStatusTransition.class)
    void theVetoedRoles_areImmutable(ServiceStatusTransition transition) {
        Set<String> roles = transition.vetoedRoles();

        assertThrows(UnsupportedOperationException.class, () -> roles.add("colado"),
            "se pudo agregar un rol a la lista de vetados de " + transition);
    }

    private static Set<ServiceStatusTransition> filter(
            java.util.function.Predicate<ServiceStatusTransition> predicate) {
        return Arrays.stream(ServiceStatusTransition.values()).filter(predicate)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(ServiceStatusTransition.class)));
    }
}
