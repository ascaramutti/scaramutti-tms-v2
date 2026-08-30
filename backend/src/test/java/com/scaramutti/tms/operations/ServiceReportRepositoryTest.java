package com.scaramutti.tms.operations;

import com.scaramutti.tms.shared.repository.ServiceReportRepository;
import com.scaramutti.tms.shared.util.DateUtils;
import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.OperationsTestData;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit del repositorio del reporte, y existe por UNA razón concreta.
 *
 * <p>Los refuerzos se piden por los IDS que devolvió la consulta de las filas, y NO re-aplicando el
 * rango de fechas. La diferencia importa: con el rango re-aplicado, cada sentencia toma su propio
 * snapshot bajo READ COMMITTED, así que una corrección de la fecha de fin entre una consulta y la
 * otra publica la fila con la lista de refuerzos vacía, en silencio, en un documento de facturación.
 *
 * <p><b>Esa diferencia NO se puede medir desde un test de integración.</b> Sembrando un viaje dentro
 * del rango y otro fuera, las dos implementaciones devuelven exactamente lo mismo: la que filtra por
 * rango también excluye al de afuera. El caso de integración que dice cubrirlo pasa idéntico con la
 * versión que tenía el defecto.
 *
 * <p>Lo que sí las distingue es preguntar por un viaje que está FUERA del rango: por ids, sus
 * refuerzos vuelven; por rango, no. Eso es lo que fija este archivo, y es imposible de satisfacer
 * para la implementación vieja.
 */
@QuarkusTest
class ServiceReportRepositoryTest {

    /** Lejos de la data real y del reloj de la suite. */
    /** Lejos de cualquier dato real: la copia de producción crece y un ancla cercana se puebla sola. */
    private static final LocalDate ANCHOR = LocalDate.of(2099, 6, 17);

    @Inject ServiceReportRepository serviceReportRepository;
    @Inject HermeticTestData fixtures;
    @Inject OperationsTestData operationsFixtures;
    @Inject WarehouseTestData warehouseFixtures;
    @Inject EntityManager entityManager;

    private int clientId;
    private int cargoTypeId;
    private int currencyId;
    private int seq;

    @BeforeEach
    void setUp() {
        clientId = fixtures.seedClient();
        cargoTypeId = fixtures.seedCargoType();
        currencyId = fixtures.currencyId("PEN");
        seq = 0;
    }

    @AfterEach
    void cleanup() {
        operationsFixtures.deleteTestServices();
        QuarkusTransaction.requiringNew().run(() -> {
            operationsFixtures.deleteTestDrivers();
            warehouseFixtures.deleteTestFleet();
            warehouseFixtures.deleteTestWorkers();
        });
        fixtures.cleanup();
    }

    /**
     * EL caso: los refuerzos se buscan POR ID, así que vuelven aunque el viaje esté fuera de
     * cualquier rango que uno pudiera consultar.
     *
     * <p>La implementación que re-aplicaba el rango devolvía cero acá: es la aserción que separa
     * una de otra por DEFECTO, y su complemento por EXCESO es
     * {@code findAdditionalDriversOf_returnsOnlyTheAskedServices}. Ninguna de las dos se puede medir
     * desde un test de integración, y por eso existe este archivo.
     */
    @Test
    void findAdditionalDriversOf_returnsThemEvenForAServiceOutsideAnyDateRange() {
        long serviceId = seedCompleted(limaNoon(ANCHOR.plusYears(2)));
        operationsFixtures.seedAdditionalAssignment(serviceId,
            operationsFixtures.seedDriver("Fuera", "DeRango"), null, null,
            "Relevo de un viaje lejano en el tiempo");

        List<ServiceReportRepository.ServiceAdditionalDriverRow> drivers =
            serviceReportRepository.findAdditionalDriversOf(List.of(serviceId));

        assertEquals(1, drivers.size(),
            "los refuerzos se piden por id: la fecha del viaje no puede excluirlos");
        assertEquals("Fuera DeRango", drivers.get(0).driverName());
        assertEquals("Relevo de un viaje lejano en el tiempo", drivers.get(0).reason());
    }

    /** Y solo los de los ids pedidos: los de otro viaje no se cuelan. */
    @Test
    void findAdditionalDriversOf_returnsOnlyTheAskedServices() {
        long asked = seedCompleted(limaNoon(ANCHOR));
        long notAsked = seedCompleted(limaNoon(ANCHOR));
        operationsFixtures.seedAdditionalAssignment(asked,
            operationsFixtures.seedDriver("Pedido", "Uno"), null, null, "Relevo del pedido");
        operationsFixtures.seedAdditionalAssignment(notAsked,
            operationsFixtures.seedDriver("NoPedido", "Dos"), null, null, "Relevo del no pedido");

        List<ServiceReportRepository.ServiceAdditionalDriverRow> drivers =
            serviceReportRepository.findAdditionalDriversOf(List.of(asked));

        assertEquals(1, drivers.size());
        assertEquals(asked, drivers.get(0).serviceId());
    }

    /**
     * La lista vacía devuelve vacío. El nombre dice solo eso a propósito: el ahorro del viaje a la
     * base es el motivo de la guarda, pero NO se mide acá y no hay que prometerlo. Borrar el corte
     * temprano deja este caso verde, porque {@code = ANY(arreglo vacío)} devuelve cero filas igual.
     */
    @Test
    void findAdditionalDriversOf_withoutServices_returnsEmpty() {
        assertEquals(List.of(), serviceReportRepository.findAdditionalDriversOf(List.of()));
    }

    // ---------- Helpers -----------------------------------------------------------

    private static OffsetDateTime limaNoon(LocalDate date) {
        return date.atStartOfDay(DateUtils.LIMA).toOffsetDateTime().plusHours(12);
    }

    private long seedCompleted(OffsetDateTime endDateTime) {
        long serviceId = QuarkusTransaction.requiringNew().call(() ->
            ((Number) entityManager.createNativeQuery(
                "INSERT INTO operaciones.services (code, client_id, origin, destination, "
                    + "tentative_date, trip_scope, cargo_type_id, weight, price, currency_id, "
                    + "status, created_by, updated_by) "
                    + "VALUES (?1, ?2, ?3, ?4, CURRENT_DATE, 'PROVINCIA', ?5, 12000, 100, ?6, "
                    + "'COMPLETED', ?7, ?7) RETURNING id")
                .setParameter(1, "ZTESTREP" + (++seq))
                .setParameter(2, clientId)
                .setParameter(3, "ZTEST repo " + seq)
                .setParameter(4, "ZTEST repo dest " + seq)
                .setParameter(5, cargoTypeId)
                .setParameter(6, currencyId)
                // El usuario se RESUELVE: las dos columnas son NOT NULL con FK contra public.users,
                // y un id literal revienta sobre una base reconstruida con una violacion que no
                // nombra la causa. El INSERT ya deja el estado, asi que no hace falta forzarlo.
                //
                // "admin" y NO "cscaramutti": el sembrador de dev garantiza admin, lcampos e
                // inactivo, y nada mas. cscaramutti solo existe en la base de desarrollo porque se
                // comparte con el sistema anterior, asi que el caso pasaria local y reventaria en la
                // CI virgen, con un rojo del ARMADO que no se lee como un problema del endpoint.
                // Mismo tropiezo ya documentado en ServiceReinforcementResourceTest.
                .setParameter(7, fixtures.userId("admin"))
                .getSingleResult()).longValue());
        operationsFixtures.forceServiceDates(serviceId, endDateTime.minusHours(6), endDateTime);

        // La siembra se VERIFICA, igual que en el gemelo de integración: el INSERT no escribe las
        // fechas, salen solo de este UPDATE. Si afectara cero filas, el caso del viaje "lejano en el
        // tiempo" seguiría verde pero midiendo un viaje SIN fechas, que es otra cosa.
        OffsetDateTime[] seeded = operationsFixtures.serviceDatesOf(serviceId);
        assertEquals(endDateTime.toInstant(), seeded[1].toInstant(),
            "la siembra no quedó como el caso la pidió: la fecha de fin no es la que se forzó");
        assertEquals(endDateTime.minusHours(6).toInstant(), seeded[0].toInstant(),
            "la siembra no quedó como el caso la pidió: la fecha de inicio no es la que se forzó");
        return serviceId;
    }
}
