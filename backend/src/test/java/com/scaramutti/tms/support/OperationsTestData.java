package com.scaramutti.tms.support;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixtures de siembra/limpieza de los tests de integración del módulo operaciones. COMPONE
 * {@link WarehouseTestData} en vez de duplicarlo: los catálogos compartidos de {@code public}
 * (trabajadores, flota, estados de recurso) ya viven ahí, y acá se agrega lo que operaciones
 * necesita y almacén no: los conductores.
 *
 * Molde hermético de la casa: todo lo sintético lleva el prefijo {@code ZTEST} y se borra en
 * el {@code @AfterEach} del test. {@code public.drivers} es COMPARTIDA con v1, así que se
 * limpia por los ids sembrados más un barrido por el trabajador asociado (mismo criterio que
 * la flota; ver {@link #deleteTestDrivers()}), y el trabajador lo borra
 * {@link WarehouseTestData#deleteTestWorkers()} DESPUÉS (es su FK).
 */
@ApplicationScoped
public class OperationsTestData {

    /** Contador de proceso para las claves únicas de la corrida (documento y licencia). */
    private static final AtomicLong SEQ = new AtomicLong(0);

    private final Set<Integer> seededDriverIds = ConcurrentHashMap.newKeySet();

    @Inject WarehouseTestData warehouseFixtures;
    @Inject EntityManager entityManager;

    /** Conductor activo, disponible, sin categoría ni teléfono. */
    public int seedDriver(String firstName, String lastName) {
        return seedDriver(firstName, lastName, null, null, WarehouseTestData.STATUS_AVAILABLE, true);
    }

    /**
     * Conductor con su trabajador asociado (de ahí sale el nombre y el teléfono del listado).
     * La licencia se genera única por corrida: la columna tiene índice UNIQUE en v1.
     */
    public int seedDriver(String firstName, String lastName, String phone, String licenseCategory,
            String statusName, boolean isActive) {
        long n = SEQ.incrementAndGet();
        int workerId = warehouseFixtures.seedWorker("ZTESTD" + n, firstName, lastName, "Conductor", true);
        if (phone != null) {
            QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE public.workers SET phone = ?1 WHERE id = ?2")
                .setParameter(1, phone).setParameter(2, workerId).executeUpdate());
        }
        int statusId = QuarkusTransaction.requiringNew().call(() -> warehouseFixtures.resourceStatusId(statusName));
        int driverId = QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO public.drivers (worker_id, license_number, category, status_id, is_active) "
                + "VALUES (?1, ?2, ?3, ?4, ?5) RETURNING id")
            .setParameter(1, workerId).setParameter(2, "ZTESTL" + n).setParameter(3, licenseCategory)
            .setParameter(4, statusId).setParameter(5, isActive)
            .getSingleResult()).intValue());
        seededDriverIds.add(driverId);
        return driverId;
    }

    /**
     * Borra los conductores de test: primero los que cuelgan de un trabajador {@code ZTEST} y
     * después los ids sembrados. El borrado por ids es el quirúrgico, pero solo alcanza a la
     * corrida viva: si una corrida local se aborta antes del {@code @AfterEach}, los ids se van
     * con la JVM y queda un conductor huérfano que hace fallar por FK el barrido de
     * trabajadores de la corrida siguiente. El primer borrado lo limpia, con el MISMO predicado
     * que ya usa ese barrido ({@link WarehouseTestData#deleteTestWorkers()}): un documento
     * {@code ZTEST} es sintético por construcción, no un prefijo que pueda pisar data real.
     *
     * <p>Fragmento idempotente que el test compone dentro de su {@code @AfterEach}, ANTES de
     * borrar los trabajadores, que son su FK.
     */
    public void deleteTestDrivers() {
        entityManager.createNativeQuery(
            "DELETE FROM public.drivers WHERE worker_id IN "
                + "(SELECT id FROM public.workers WHERE document_number LIKE 'ZTEST%')")
            .executeUpdate();
        if (seededDriverIds.isEmpty()) {
            return;
        }
        entityManager.createNativeQuery("DELETE FROM public.drivers WHERE id IN (?1)")
            .setParameter(1, List.copyOf(seededDriverIds)).executeUpdate();
        seededDriverIds.clear();
    }
}
