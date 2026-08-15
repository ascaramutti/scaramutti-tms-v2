package com.scaramutti.tms.support;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;

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

    /**
     * Borra los servicios de transporte que cuelgan de un cliente de test ({@code ZTEST}), con
     * su bitácora, su auditoría y sus refuerzos (las FK del módulo son ON DELETE CASCADE). Los
     * servicios no tienen prefijo propio: su código lo deriva el backend del id, así que el
     * cliente sintético es lo que los hace reconocibles.
     *
     * <p>Fragmento idempotente que el test compone en su {@code @AfterEach} ANTES de
     * {@link HermeticTestData#cleanup()}, que borra esos clientes (son su FK).
     */
    public void deleteTestServices() {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
            "DELETE FROM operaciones.services WHERE client_id IN "
                + "(SELECT id FROM public.clients WHERE name LIKE 'ZTEST%')").executeUpdate());
    }

    /**
     * Fuerza el estado de un servicio por SQL. Los estados los mueve el endpoint de transiciones,
     * que es OTRO endpoint, asi que todo test que necesite un viaje que no sea recien creado
     * pasa por aca. Segunda clase que lo necesita: extraida al fixture (la variante por codigo del
     * listado no es identica y sigue en su clase).
     *
     * <p>No toca {@code updated_at} A PROPOSITO, y por eso NO sirve de molde para el script del
     * cutover: los tests dependen de que el ETag no se mueva, pero una escritura de produccion que
     * dejara la version quieta haria que un cliente con el ETag viejo siguiera pasando el
     * If-Match. Ver el javadoc del gancho en la entity.
     */
    public void forceServiceStatus(long serviceId, String status) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.services SET status = ?1 WHERE id = ?2")
            .setParameter(1, status).setParameter(2, serviceId).executeUpdate());
    }

    /**
     * Fuerza uno de los textos libres de un servicio por SQL, para fabricar el dato que el sistema
     * anterior deja y el alta de v2 no: un texto con espacios de relleno. Misma advertencia que el
     * de arriba.
     *
     * <p>La columna se interpola porque {@code UPDATE} no admite un parametro donde va un nombre
     * de columna; el valor sigue yendo parametrizado y los nombres salen de un conjunto cerrado.
     */
    public void forceFreeText(long serviceId, String column, String value) {
        if (!FREE_TEXT_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("columna de texto libre desconocida: " + column);
        }
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.services SET " + column + " = ?1 WHERE id = ?2")
            .setParameter(1, value).setParameter(2, serviceId).executeUpdate());
    }

    private static final java.util.Set<String> FREE_TEXT_COLUMNS =
        java.util.Set.of("origin", "destination", "observations");

    /**
     * Fuerza las fechas reales por SQL. Misma advertencia que los dos de arriba sobre
     * {@code updated_at}.
     *
     * <p>Hace falta para fabricar la fila que la aplicacion NO produce: un viaje en ruta con su
     * inicio ya puesto por el cutover del sistema anterior, o directamente SIN inicio. Sin esto,
     * todo caso de "el fin no puede ser anterior al inicio" queda encadenado a que la transicion
     * de inicio haya corrido antes, y un defecto que afecte a los dos caminos a la vez no lo ve
     * nadie: el test lo estaria midiendo contra el mismo codigo que prueba.
     */
    public void forceServiceDates(long serviceId, OffsetDateTime start, OffsetDateTime end) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.services SET start_date_time = ?1, end_date_time = ?2 "
                    + "WHERE id = ?3")
            .setParameter(1, start).setParameter(2, end).setParameter(3, serviceId).executeUpdate());
    }

    /**
     * Fuerza los recursos principales de un viaje por SQL. Misma advertencia que los de arriba
     * sobre {@code updated_at}.
     *
     * <p>Hace falta para dejar la fila como la deja la ASIGNACION sin encadenar ese endpoint: un
     * viaje en "pendiente de inicio" siempre tiene conductor y tracto, porque es la asignacion la
     * que lo lleva a ese estado. Un fixture que fuerce el estado y no los recursos fabrica una fila
     * que la aplicacion no puede producir, y entonces los tests miden comportamiento sobre un
     * estado inexistente.
     */
    public void forceServiceResources(long serviceId, Integer driverId, Integer tractorId,
            Integer trailerId) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.services SET driver_id = ?1, tractor_id = ?2, trailer_id = ?3 "
                    + "WHERE id = ?4")
            .setParameter(1, driverId).setParameter(2, tractorId).setParameter(3, trailerId)
            .setParameter(4, serviceId).executeUpdate());
    }

    /**
     * Tracto de operaciones, con placa propia de la corrida ({@code ZO00xx}). La placa la genera
     * el fixture y no el test: {@code public.tractors.plate} es UNIQUE y esta suite necesita
     * varias unidades por caso (el conflicto exige un recurso ocupado y otro libre), así que con
     * placas literales se chocarían entre sí.
     */
    public int seedTractor() {
        return seedTractor(true, WarehouseTestData.STATUS_AVAILABLE);
    }

    public int seedTractor(boolean isActive, String statusName) {
        return warehouseFixtures.seedTractor(nextOperationsPlate(), isActive, null, null, statusName);
    }

    /** Carreta de operaciones, con placa propia de la corrida. Mismo motivo que el de arriba. */
    public int seedTrailer() {
        return seedTrailer(true, WarehouseTestData.STATUS_AVAILABLE);
    }

    public int seedTrailer(boolean isActive, String statusName) {
        return warehouseFixtures.seedTrailer(nextOperationsPlate(), isActive, statusName);
    }

    /**
     * Unidad con una placa DICTADA por el test, para fabricar el dato que el sistema anterior sí
     * puede escribir y el alta de v2 no: una placa con un salto de línea adentro. La columna es
     * {@code VARCHAR} sin restricción de formato, así que la base lo admite; la limpieza los
     * alcanza igual porque se borran por el id sembrado.
     */
    public int seedTractorWithPlate(String plate) {
        return warehouseFixtures.seedTractor(plate, true, null, null, WarehouseTestData.STATUS_AVAILABLE);
    }

    public int seedTrailerWithPlate(String plate) {
        return warehouseFixtures.seedTrailer(plate, true, WarehouseTestData.STATUS_AVAILABLE);
    }

    /**
     * Placa reservada de operaciones: {@code ZO} + cuatro dígitos. El contador es de proceso, así
     * que dos casos de la misma corrida nunca piden la misma.
     */
    private String nextOperationsPlate() {
        return String.format("ZO%04d", SEQ.incrementAndGet() % 10000);
    }

    /**
     * Recurso de REFUERZO de un viaje ({@code operaciones.service_assignments}), por SQL.
     *
     * <p>Es el fixture que hace verificable la segunda fuente del conflicto: fabrica un viaje que
     * retiene un recurso SOLO como refuerzo, que es justamente el caso que el sistema anterior no
     * miraba.
     *
     * <p>Sigue haciendo falta aunque el endpoint de refuerzos ya exista: aquel solo escribe sobre
     * viajes EN RUTA, y varios casos necesitan un refuerzo colgado de un viaje en otro estado
     * (el retenedor en "pendiente de inicio", por ejemplo). Encadenar el endpoint tampoco serviría
     * para los tests de ese mismo endpoint: mediría su salida contra su propia entrada.
     *
     * <p>{@code assigned_by} sale del creador del viaje: la columna es NOT NULL y cualquier
     * usuario sirve, pero tomarlo del propio viaje evita sembrar uno aparte.
     */
    public long seedAdditionalAssignment(long serviceId, Integer driverId, Integer tractorId,
            Integer trailerId, String reason) {
        return seedAdditionalAssignment(serviceId, driverId, tractorId, trailerId, reason, null);
    }

    /**
     * Variante que DICTA el momento del refuerzo. Sin ella no se puede fijar el orden del listado:
     * sembrando en orden, PostgreSQL devuelve las filas en el orden fisico —que en una tabla recien
     * escrita es el de insercion— y un {@code ORDER BY} borrado pasaria igual.
     */
    public long seedAdditionalAssignment(long serviceId, Integer driverId, Integer tractorId,
            Integer trailerId, String reason, OffsetDateTime assignedAt) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
            "INSERT INTO operaciones.service_assignments "
                + "(service_id, driver_id, tractor_id, trailer_id, reason, assigned_by, assigned_at) "
                + "SELECT ?1, ?2, ?3, ?4, ?5, s.created_by, COALESCE(?6, CURRENT_TIMESTAMP) "
                + "FROM operaciones.services s WHERE s.id = ?1 "
                + "RETURNING id")
            .setParameter(1, serviceId).setParameter(2, driverId).setParameter(3, tractorId)
            .setParameter(4, trailerId).setParameter(5, reason).setParameter(6, assignedAt)
            .getSingleResult()).longValue());
    }

    /**
     * Reescribe una fila de refuerzo SIN cambiar ningún valor, para moverla al final del montón.
     *
     * <p>Existe para un solo caso: fijar el desempate por {@code id} del listado. Sembrando en
     * orden, el orden de {@code id} y el orden físico COINCIDEN, así que la consulta devuelve lo
     * mismo con y sin el desempate. Con este UPDATE, MVCC escribe una versión nueva de la primera
     * fila al final, y los dos órdenes quedan invertidos: recién ahí el desempate se puede medir.
     */
    public void rewriteAssignmentInPlace(long assignmentId) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE operaciones.service_assignments SET reason = reason WHERE id = ?1")
            .setParameter(1, assignmentId).executeUpdate());
    }

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
