package com.scaramutti.tms.operations.api;

import jakarta.annotation.security.RolesAllowed;
import com.scaramutti.tms.operations.model.ServiceStatusChangeAuthorizationRoles;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La relacion entre las listas de roles del recurso, verificada por reflexion.
 *
 * <p>Existe por un invariante de SEGURIDAD que hasta ahora vivia solo en prosa: el cuerpo del
 * conflicto de asignacion nombra el codigo y el estado de OTRO viaje, y eso no filtra nada
 * unicamente porque cualquiera que pueda asignar ya puede leer ese viaje entero por la puerta de
 * adelante. Sumar un rol a la asignacion sin sumarlo al detalle convierte ese 409 en un canal de
 * lectura, y ningun test de los que enumeran roles lo notaria: cada uno afirma SU lista, no la
 * relacion entre las dos.
 */
class ServiceResourceRolesTest {

    @Test
    void assignmentRoles_areASubsetOfTheDetailRoles() {
        Set<String> assignment = rolesOf("assignServiceResources");
        Set<String> detail = rolesOf("getService");

        assertTrue(detail.containsAll(assignment),
            "quien puede asignar tiene que poder leer el detalle, porque el cuerpo del conflicto "
                + "nombra otro viaje. Sobran en la asignacion: "
                + assignment.stream().filter(role -> !detail.contains(role)).collect(Collectors.toSet()));
    }

    /**
     * La lista que deja entrar al endpoint y la que el veto usa como base son DOS literales en dos
     * archivos, y el javadoc de la constante promete que son la misma. La deriva es fail-closed
     * —un rol agregado solo a la anotación entra por la puerta y cae con 403 desde adentro— pero
     * es silenciosa: el contrato y la anotación dirían que puede, y ningún otro test lo ve.
     */
    @Test
    void theOperatingRolesConstant_matchesTheAnnotationItClaimsToMirror() {
        assertEquals(ServiceStatusChangeAuthorizationRoles.OPERATING_ROLES,
            rolesOf("changeServiceStatus"),
            "la constante y el @RolesAllowed del endpoint dejaron de decir lo mismo");
    }

    /**
     * Las transiciones devuelven el detalle COMPLETO, con su bitácora. Un rol que solo pudiera
     * transicionar leería por esa respuesta un viaje que no puede pedir por la puerta de adelante.
     */
    @Test
    void statusRoles_areASubsetOfTheDetailRoles() {
        Set<String> status = rolesOf("changeServiceStatus");
        Set<String> detail = rolesOf("getService");

        assertTrue(detail.containsAll(status),
            "quien puede transicionar tiene que poder leer el detalle: el 200 ES el detalle. "
                + "Sobran en las transiciones: "
                + status.stream().filter(role -> !detail.contains(role)).collect(Collectors.toSet()));
    }

    /** Y lo mismo para el listado, que es la otra puerta por la que se lee un viaje ajeno. */
    @Test
    void assignmentRoles_areASubsetOfTheListRoles() {
        Set<String> assignment = rolesOf("assignServiceResources");
        Set<String> list = rolesOf("listServices");

        assertTrue(list.containsAll(assignment),
            "sobran en la asignacion: "
                + assignment.stream().filter(role -> !list.contains(role)).collect(Collectors.toSet()));
    }

    /**
     * El item del conflicto lleva un TERCER dato además del código y el estado: el nombre del
     * conductor o la placa de la unidad. Eso sale de los catálogos compartidos, que mantiene otro
     * módulo. Si una limpieza de ese lado recorta su lista de roles, la asignación queda como el
     * único camino por el que un rol lee placas, y nada se pondría rojo.
     */
    @Test
    void assignmentRoles_areASubsetOfTheSharedCatalogRoles() {
        Set<String> assignment = rolesOf(ServiceResource.class, "assignServiceResources");
        Set<String> drivers = rolesOf(
            com.scaramutti.tms.sharedcatalogs.driver.api.DriverResource.class, "listDrivers");
        Set<String> fleetUnits = rolesOf(
            com.scaramutti.tms.sharedcatalogs.fleetunit.api.FleetUnitResource.class, "listFleetUnits");

        assertTrue(drivers.containsAll(assignment),
            "quien asigna tiene que poder leer el catalogo de conductores; sobran: "
                + assignment.stream().filter(role -> !drivers.contains(role)).collect(Collectors.toSet()));
        assertTrue(fleetUnits.containsAll(assignment),
            "quien asigna tiene que poder leer el catalogo de flota; sobran: "
                + assignment.stream().filter(role -> !fleetUnits.contains(role)).collect(Collectors.toSet()));
    }

    /**
     * Y lo mismo para las transiciones, que producen el MISMO cuerpo de conflicto que la asignacion
     * —nombre del conductor y placas, que salen de los catalogos compartidos— y ademas persisten
     * esa placa en la bitacora, que sobrevive a la respuesta. Hoy las dos listas son identicas, asi
     * que el caso de la asignacion cubre a este por transitividad; esa igualdad no la afirma nadie.
     */
    @Test
    void statusRoles_areASubsetOfTheSharedCatalogRoles() {
        Set<String> status = rolesOf(ServiceResource.class, "changeServiceStatus");
        Set<String> drivers = rolesOf(
            com.scaramutti.tms.sharedcatalogs.driver.api.DriverResource.class, "listDrivers");
        Set<String> fleetUnits = rolesOf(
            com.scaramutti.tms.sharedcatalogs.fleetunit.api.FleetUnitResource.class, "listFleetUnits");

        assertTrue(drivers.containsAll(status),
            "quien transiciona lee nombres de conductor por el conflicto; sobran: "
                + status.stream().filter(role -> !drivers.contains(role)).collect(Collectors.toSet()));
        assertTrue(fleetUnits.containsAll(status),
            "y placas de flota; sobran: "
                + status.stream().filter(role -> !fleetUnits.contains(role)).collect(Collectors.toSet()));
    }

    /**
     * Los REFUERZOS producen el mismo cuerpo de conflicto que la asignación —código y estado de
     * OTRO viaje— y además persisten la placa en la bitácora, que sobrevive a la respuesta. Le
     * aplican las mismas cuatro precondiciones, y su javadoc las promete explícitamente.
     */
    @Test
    void reinforcementRoles_areASubsetOfTheDetailRoles() {
        Set<String> reinforcement = rolesOf("addServiceResources");
        Set<String> detail = rolesOf("getService");

        assertTrue(detail.containsAll(reinforcement),
            "quien suma refuerzos tiene que poder leer el detalle: el 200 ES el detalle, y el 409 "
                + "nombra otro viaje. Sobran en los refuerzos: "
                + reinforcement.stream().filter(role -> !detail.contains(role)).collect(Collectors.toSet()));
    }

    @Test
    void reinforcementRoles_areASubsetOfTheListRoles() {
        Set<String> reinforcement = rolesOf("addServiceResources");
        Set<String> list = rolesOf("listServices");

        assertTrue(list.containsAll(reinforcement),
            "sobran en los refuerzos: "
                + reinforcement.stream().filter(role -> !list.contains(role)).collect(Collectors.toSet()));
    }

    @Test
    void reinforcementRoles_areASubsetOfTheSharedCatalogRoles() {
        Set<String> reinforcement = rolesOf(ServiceResource.class, "addServiceResources");
        Set<String> drivers = rolesOf(
            com.scaramutti.tms.sharedcatalogs.driver.api.DriverResource.class, "listDrivers");
        Set<String> fleetUnits = rolesOf(
            com.scaramutti.tms.sharedcatalogs.fleetunit.api.FleetUnitResource.class, "listFleetUnits");

        assertTrue(drivers.containsAll(reinforcement),
            "quien suma refuerzos lee nombres de conductor por el conflicto y por la bitacora; sobran: "
                + reinforcement.stream().filter(role -> !drivers.contains(role)).collect(Collectors.toSet()));
        assertTrue(fleetUnits.containsAll(reinforcement),
            "y placas de flota; sobran: "
                + reinforcement.stream().filter(role -> !fleetUnits.contains(role)).collect(Collectors.toSet()));
    }

    /**
     * Y la simetría con la asignación: son la misma operación sobre el mismo viaje (elegir recursos)
     * y el contrato les da la misma lista. Si alguna vez divergen, que sea una decisión escrita.
     */
    @Test
    void reinforcementRoles_matchTheAssignmentRoles() {
        assertEquals(rolesOf("assignServiceResources"), rolesOf("addServiceResources"),
            "asignar y reforzar son la misma decisión operativa; el contrato les da la misma lista");
    }

    private Set<String> rolesOf(String methodName) {
        return rolesOf(ServiceResource.class, methodName);
    }

    private Set<String> rolesOf(Class<?> resourceClass, String methodName) {
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
