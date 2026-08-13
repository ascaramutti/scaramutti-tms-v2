package com.scaramutti.tms.support;

import com.scaramutti.tms.shared.entity.Product;
import com.scaramutti.tms.shared.entity.Supplier;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.ProductRepository;
import com.scaramutti.tms.shared.repository.SupplierRepository;
import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.http.ContentType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.restassured.RestAssured.given;

/**
 * Fixtures de siembra/limpieza compartidos por los tests de integración del módulo almacén y
 * de los catálogos compartidos de flota/trabajadores. Antes cada clase de test redeclaraba
 * estos helpers (seedProduct, seedSupplier, seedWorker, seedTractor/Trailer/Escort,
 * los get-or-create de document_types/resource_statuses y las limpiezas por prefijo); acá viven
 * una sola vez, como bean CDI ({@code @Inject WarehouseTestData fixtures}).
 *
 * Molde hermético (igual que {@link HermeticTestData}): todo se siembra con prefijo
 * {@code ZTEST_} (o placa/documento propios) y se borra por ese prefijo en el {@code @AfterEach}
 * del test. Las tablas de {@code public} (workers, tractors, trailers, escort_vehicles,
 * document_types, resource_statuses) son COMPARTIDAS con v1: por eso la flota se limpia por los
 * ids que estos fixtures sembraron, más un barrido de respaldo por el rango de placas reservado
 * para los tests (ver {@link #deleteTestFleet()}).
 */
@ApplicationScoped
public class WarehouseTestData {

    public static final String PREFIX = "ZTEST_";

    /**
     * Rango de placas que los tests reservan: {@code ZF00xx} (flota), {@code ZT0xxx} (retiros),
     * {@code ZR00xx} (reportes) y {@code ZO00xx} (operaciones). Dos letras y cuatro dígitos, un
     * formato que ninguna placa real puede tener (las peruanas son tres letras y tres dígitos).
     *
     * <p>Cada suite usa su propia segunda letra para que el barrido de respaldo de una no pise
     * las unidades que otra tiene vivas mientras corre.
     */
    private static final String TEST_PLATE_PATTERN = "^Z[FTRO][0-9]{4}$";

    /** Nombres reales de {@code public.resource_statuses} (el catálogo de v1 tiene estas tres filas). */
    public static final String STATUS_AVAILABLE = "available";
    public static final String STATUS_MAINTENANCE = "maintenance";
    public static final String STATUS_NOT_AVAILABLE = "not_available";

    // Catálogos base sembrados por el DevDataSeeder (mismos ids en dev y en la CI virgen).
    private static final int CATEGORY_FILTROS = 4;
    private static final int UNIT_UND = 1;

    // Ids de la flota sembrada, para borrarla uno por uno sin rozar la de v1. El bean es
    // @ApplicationScoped (uno solo para toda la corrida) y cada @AfterEach vacía los sets.
    private final Set<Integer> seededTractorIds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> seededTrailerIds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> seededEscortVehicleIds = ConcurrentHashMap.newKeySet();

    @Inject ProductRepository productRepository;
    @Inject SupplierRepository supplierRepository;
    @Inject WorkerRepository workerRepository;
    @Inject UserRepository userRepository;
    @Inject EntityManager entityManager;

    // ---------- lookups ---------------------------------------------------------

    /** Id del usuario {@code admin} (registrador por defecto de los fixtures). */
    public int adminId() {
        return userRepository.findByUsername("admin").orElseThrow().id;
    }

    /** Id de la moneda sembrada por code ({@code USD}/{@code PEN}). */
    public int currencyId(String code) {
        return ((Number) entityManager.createNativeQuery(
            "SELECT id FROM public.currencies WHERE code = ?1").setParameter(1, code)
            .getSingleResult()).intValue();
    }

    /**
     * Id del tipo de documento {@code DNI}, get-or-create: {@code public.document_types} es una
     * tabla de v1 sin seed en la BD virgen de CI, así que asumir una fila existente fallaba solo
     * en CI.
     */
    public int dniDocumentTypeId() {
        var rows = entityManager.createNativeQuery("SELECT id FROM public.document_types WHERE code = 'DNI'")
            .getResultList();
        if (!rows.isEmpty()) {
            return ((Number) rows.get(0)).intValue();
        }
        entityManager.createNativeQuery(
            "INSERT INTO public.document_types (code, name, max_length, is_active) VALUES ('DNI', 'DNI', 8, true)")
            .executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT id FROM public.document_types WHERE code = 'DNI'")
            .getSingleResult()).intValue();
    }

    /**
     * Estado de recurso por nombre, get-or-create como {@link #dniDocumentTypeId()}:
     * {@code public.resource_statuses} es una tabla de v1 sin seed en la BD virgen de CI. La
     * flota y los conductores lo referencian por FK ({@code status_id}).
     *
     * <p>Los nombres son los REALES del catálogo ({@code available}, {@code maintenance},
     * {@code not_available}), no uno sintético: la API traduce ese nombre a su enum de
     * disponibilidad y revienta ante cualquier otro, así que un fixture inventado rompería el
     * listado. Las filas que crea quedan (son catálogo legítimo e idempotente); lo que se
     * limpia es la flota.
     */
    public int resourceStatusId(String catalogName) {
        var rows = entityManager.createNativeQuery(
            "SELECT id FROM public.resource_statuses WHERE name = ?1")
            .setParameter(1, catalogName).getResultList();
        if (!rows.isEmpty()) {
            return ((Number) rows.get(0)).intValue();
        }
        entityManager.createNativeQuery(
            "INSERT INTO public.resource_statuses (name, is_active) VALUES (?1, true)")
            .setParameter(1, catalogName).executeUpdate();
        return ((Number) entityManager.createNativeQuery(
            "SELECT id FROM public.resource_statuses WHERE name = ?1")
            .setParameter(1, catalogName).getSingleResult()).intValue();
    }

    /** Estado por defecto de la flota de test: disponible. */
    public int resourceStatusId() {
        return resourceStatusId(STATUS_AVAILABLE);
    }

    // ---------- productos -------------------------------------------------------

    /** Producto activo con {@code minStock} 0. */
    public int seedProduct(String name) {
        return seedProduct(name, "0", true);
    }

    /** Producto con {@code minStock} 0 e {@code isActive} explícito. */
    public int seedProduct(String name, boolean isActive) {
        return seedProduct(name, "0", isActive);
    }

    /** Producto con {@code minStock} y {@code isActive} explícitos. */
    public int seedProduct(String name, String minStock, boolean isActive) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Product product = new Product();
            product.name = name;
            product.categoryId = CATEGORY_FILTROS;
            product.unitOfMeasureId = UNIT_UND;
            product.attributes = new HashMap<>();
            product.minStock = new BigDecimal(minStock);
            product.isActive = isActive;
            product.createdBy = adminId();
            productRepository.persist(product);
            return product.id;
        });
    }

    // ---------- proveedores -----------------------------------------------------

    public int seedSupplier(String name) {
        return seedSupplier(name, true);
    }

    public int seedSupplier(String name, boolean isActive) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Supplier supplier = new Supplier();
            supplier.name = name;
            supplier.isActive = isActive;
            supplierRepository.persist(supplier);
            return supplier.id;
        });
    }

    // ---------- trabajadores ----------------------------------------------------

    /** Operario activo genérico ({@code ZTEST}/{@code Operario}). */
    public int seedWorker(String documentNumber) {
        return seedWorker(documentNumber, "ZTEST", "Operario", "ZTEST Operario", true);
    }

    /** Operario genérico con {@code isActive} explícito. */
    public int seedWorker(String documentNumber, boolean isActive) {
        return seedWorker(documentNumber, "ZTEST", "Operario", "ZTEST Operario", isActive);
    }

    /** Trabajador con nombre/apellido/puesto/estado explícitos. */
    public int seedWorker(String documentNumber, String firstName, String lastName, String position,
            boolean isActive) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Worker worker = new Worker();
            worker.firstName = firstName;
            worker.lastName = lastName;
            worker.documentTypeId = dniDocumentTypeId();
            worker.documentNumber = documentNumber;
            worker.position = position;
            worker.isActive = isActive;
            worker.createdAt = OffsetDateTime.now();
            workerRepository.persist(worker);
            return worker.id;
        });
    }

    // ---------- flota (tractores / semirremolques / escoltas) -------------------
    // public.tractors/trailers/escort_vehicles son COMPARTIDAS con v1: cada id sembrado se
    // anota para borrarlo uno por uno al final. brand/model son opcionales (solo /fleet-units
    // los verifica); el estado por defecto es "disponible".

    public int seedTractor(String plate) {
        return seedTractor(plate, true);
    }

    public int seedTractor(String plate, boolean isActive) {
        return seedTractor(plate, isActive, null, null, STATUS_AVAILABLE);
    }

    public int seedTractor(String plate, boolean isActive, String brand, String model) {
        return seedTractor(plate, isActive, brand, model, STATUS_AVAILABLE);
    }

    public int seedTractor(String plate, boolean isActive, String brand, String model, String statusName) {
        return trackFleetId(seededTractorIds, QuarkusTransaction.requiringNew().call(() ->
            ((Number) entityManager.createNativeQuery(
                "INSERT INTO public.tractors (plate, brand, model, status_id, is_active) "
                    + "VALUES (?1, ?2, ?3, ?4, ?5) RETURNING id")
                .setParameter(1, plate).setParameter(2, brand).setParameter(3, model)
                .setParameter(4, resourceStatusId(statusName)).setParameter(5, isActive)
                .getSingleResult()).intValue()));
    }

    public int seedTrailer(String plate) {
        return seedTrailer(plate, true);
    }

    public int seedTrailer(String plate, boolean isActive) {
        return seedTrailer(plate, isActive, STATUS_AVAILABLE);
    }

    public int seedTrailer(String plate, boolean isActive, String statusName) {
        return trackFleetId(seededTrailerIds, QuarkusTransaction.requiringNew().call(() ->
            ((Number) entityManager.createNativeQuery(
                "INSERT INTO public.trailers (plate, type, status_id, is_active) "
                    + "VALUES (?1, 'ZTEST', ?2, ?3) RETURNING id")
                .setParameter(1, plate).setParameter(2, resourceStatusId(statusName)).setParameter(3, isActive)
                .getSingleResult()).intValue()));
    }

    public int seedEscortVehicle(String plate) {
        return seedEscortVehicle(plate, true);
    }

    public int seedEscortVehicle(String plate, boolean isActive) {
        return seedEscortVehicle(plate, isActive, null, null);
    }

    public int seedEscortVehicle(String plate, boolean isActive, String brand, String model) {
        return trackFleetId(seededEscortVehicleIds, QuarkusTransaction.requiringNew().call(() ->
            ((Number) entityManager.createNativeQuery(
                "INSERT INTO public.escort_vehicles (plate, brand, model, status_id, is_active) "
                    + "VALUES (?1, ?2, ?3, ?4, ?5) RETURNING id")
                .setParameter(1, plate).setParameter(2, brand).setParameter(3, model)
                .setParameter(4, resourceStatusId()).setParameter(5, isActive)
                .getSingleResult()).intValue()));
    }

    private int trackFleetId(Set<Integer> seededIds, int id) {
        seededIds.add(id);
        return id;
    }

    // ---------- stock vía endpoint ----------------------------------------------

    /** Corte inicial vía {@code POST /warehouse/opening-balances}: da stock al producto. */
    public void seedOpeningBalance(int productId, String quantity, String token) {
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body("{\"productId\":" + productId + ",\"quantity\":" + quantity + "}")
        .when().post("/warehouse/opening-balances").then().statusCode(201);
    }

    public void seedOpeningBalance(int productId, int quantity, String token) {
        seedOpeningBalance(productId, String.valueOf(quantity), token);
    }

    /** Stock actual del producto vía {@code GET /warehouse/products/{id}/stock}. */
    public BigDecimal stockOf(int productId, String token) {
        return given().header("Authorization", "Bearer " + token)
        .when().get("/warehouse/products/" + productId + "/stock")
        .then().statusCode(200).extract().jsonPath().getObject("stock", BigDecimal.class);
    }

    /** Afirma (por valor numérico, ignorando escala) que el stock del producto es {@code expected}. */
    public void assertStock(int productId, String expected, String token) {
        BigDecimal actual = stockOf(productId, token);
        if (actual.compareTo(new BigDecimal(expected)) != 0) {
            throw new AssertionError("Stock de " + productId + " esperado " + expected + " pero fue " + actual);
        }
    }

    // ---------- limpieza --------------------------------------------------------
    // Fragmentos idempotentes (borran 0 filas si el test no sembró esa tabla). NO abren su
    // propia transacción: el test los compone dentro de su @AfterEach
    // (QuarkusTransaction.requiringNew().run(() -> { ... })), conservando orden y atomicidad.

    /**
     * Borra en orden FK toda la data {@code ZTEST_} del schema {@code almacen} (movimientos,
     * ítems, facturas, cortes iniciales, productos, proveedores). Seguro aunque el test no haya
     * sembrado alguna de esas tablas.
     */
    public void deleteWarehouseTestData() {
        entityManager.createNativeQuery(
            "DELETE FROM almacen.withdrawals WHERE product_id IN "
                + "(SELECT id FROM almacen.products WHERE name LIKE ?1)")
            .setParameter(1, PREFIX + "%").executeUpdate();
        entityManager.createNativeQuery(
            "DELETE FROM almacen.purchase_invoice_items WHERE invoice_id IN "
                + "(SELECT id FROM almacen.purchase_invoices WHERE supplier_id IN "
                + "(SELECT id FROM almacen.suppliers WHERE name LIKE ?1))")
            .setParameter(1, PREFIX + "%").executeUpdate();
        entityManager.createNativeQuery(
            "DELETE FROM almacen.purchase_invoices WHERE supplier_id IN "
                + "(SELECT id FROM almacen.suppliers WHERE name LIKE ?1)")
            .setParameter(1, PREFIX + "%").executeUpdate();
        entityManager.createNativeQuery(
            "DELETE FROM almacen.opening_balances WHERE product_id IN "
                + "(SELECT id FROM almacen.products WHERE name LIKE ?1)")
            .setParameter(1, PREFIX + "%").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM almacen.products WHERE name LIKE ?1")
            .setParameter(1, PREFIX + "%").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM almacen.suppliers WHERE name LIKE ?1")
            .setParameter(1, PREFIX + "%").executeUpdate();
    }

    /**
     * Borrado de la flota de test: primero un barrido por el rango de placas que los tests
     * reservan y después los ids que estos fixtures sembraron. El borrado por ids es el
     * quirúrgico (no depende de ningún prefijo), pero solo alcanza a la corrida viva: si una
     * corrida local se aborta antes del {@code @AfterEach}, los ids se van con la JVM y la
     * flota queda huérfana en la BD compartida. El barrido la limpia en la corrida siguiente.
     *
     * <p>Ahí el prefijo SÍ es seguro, a diferencia del genérico que este soporte evita: el
     * rango reservado son dos letras y CUATRO dígitos ({@code ZF}/{@code ZT}/{@code ZR}), un
     * formato que ninguna placa real puede tener (las peruanas son tres letras y tres dígitos).
     * El catálogo de estados no se toca.
     */
    public void deleteTestFleet() {
        deleteFleetRows("public.tractors", seededTractorIds);
        deleteFleetRows("public.trailers", seededTrailerIds);
        deleteFleetRows("public.escort_vehicles", seededEscortVehicleIds);
    }

    private void deleteFleetRows(String table, Set<Integer> seededIds) {
        entityManager.createNativeQuery("DELETE FROM " + table + " WHERE plate ~ ?1")
            .setParameter(1, TEST_PLATE_PATTERN).executeUpdate();
        // Segundo barrido, para las placas que los tests fabrican CON caracteres de control: no
        // matchean el patron de arriba (el salto no es un digito) y, si una corrida se aborta
        // antes del @AfterEach, quedan para siempre en la base que se comparte con el sistema
        // anterior y hacen reventar por unicidad a la corrida siguiente.
        entityManager.createNativeQuery(
                "DELETE FROM " + table + " WHERE plate ~ '^Z[FTRO]' AND plate ~ '[[:cntrl:]]'")
            .executeUpdate();
        if (seededIds.isEmpty()) {
            return;
        }
        entityManager.createNativeQuery("DELETE FROM " + table + " WHERE id IN (?1)")
            .setParameter(1, List.copyOf(seededIds)).executeUpdate();
        seededIds.clear();
    }

    /** Borra los trabajadores de test ({@code document_number} prefijo {@code ZTEST}). */
    public void deleteTestWorkers() {
        entityManager.createNativeQuery("DELETE FROM public.workers WHERE document_number LIKE 'ZTEST%'")
            .executeUpdate();
    }
}
