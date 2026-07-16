package com.scaramutti.tms.sharedcatalogs.worker;

import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests de GET /workers. Hermetico: los workers de test se siembran con
 * {@code document_number} prefijo ZTEST y se limpian en {@code @AfterEach}. La tabla
 * {@code public.workers} es COMPARTIDA con v1 (tiene data real), asi que las aserciones
 * "sin filtro" son por PRESENCIA del registro sembrado (find/hasItem), nunca por tamano.
 */
@QuarkusTest
class WorkersResourceTest {

    @Inject WorkerRepository workerRepository;
    @Inject EntityManager entityManager;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery("DELETE FROM public.workers WHERE document_number LIKE 'ZTEST%'")
                .executeUpdate());
    }

    // ---------- fixtures --------------------------------------------------------

    private int seedWorker(String documentNumber, String firstName, String lastName, String position, boolean isActive) {
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

    private int dniDocumentTypeId() {
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

    private String login(String username, String password) {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .when().post("/auth/login").then().statusCode(200).extract().jsonPath().getString("token");
    }

    private String fabricateAccessToken(String username, String role) {
        Instant now = Instant.now();
        return Jwt.subject("999").upn(username).groups(Set.of(role)).claim("typ", "access")
            .issuedAt(now).expiresAt(now.plusSeconds(3600)).sign();
    }

    private String adminToken() {
        return login("admin", "Admin1234");
    }

    // ---------- happy path -------------------------------------------------------

    @Test
    void listWorkers_returnsSeededWorkerWithComposedFullName() {
        int id = seedWorker("ZTESTW900", "Juan", "Perez", "Mecánico", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/workers")
        .then().statusCode(200)
            .body("find { it.id == " + id + " }.fullName", equalTo("Juan Perez"))
            .body("find { it.id == " + id + " }.position", equalTo("Mecánico"))
            .body("find { it.id == " + id + " }.isActive", equalTo(true));
    }

    // ---------- q filter ---------------------------------------------------------

    @Test
    void listWorkers_qMatchesPartialNameCaseInsensitive() {
        int id = seedWorker("ZTESTW902", "Carlos", "Ramirez", "Chofer", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("q", "carlos")
        .when().get("/workers")
        .then().statusCode(200)
            .body("id", hasItem(id))
            .body("find { it.id == " + id + " }.fullName", equalTo("Carlos Ramirez"));
    }

    @Test
    void listWorkers_qMultiWordMatchesFirstAndLastName() {
        int id = seedWorker("ZTESTW903", "Juan", "Perez", "Mecánico", true);
        String token = adminToken();

        // "juan perez": cada palabra matchea first_name O last_name (MultiWordSearch, AND de ORs)
        given().header("Authorization", "Bearer " + token).queryParam("q", "juan perez")
        .when().get("/workers")
        .then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    void listWorkers_qNoMatchReturnsEmptyArray() {
        seedWorker("ZTESTW910", "Ana", "Silva", "Ayudante", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("q", "zzzznomatch999")
        .when().get("/workers")
        .then().statusCode(200).body("$", empty());
    }

    @Test
    void listWorkers_qShorterThanMinLengthReturns400_COM001() {
        String token = adminToken();
        given().header("Authorization", "Bearer " + token).queryParam("q", "ab")
        .when().get("/workers")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    // ---------- isActive filter --------------------------------------------------

    @Test
    void listWorkers_isActiveFalseIncludesInactive() {
        int id = seedWorker("ZTESTW904", "Ines", "Torres", "Ayudante", false);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("isActive", false)
        .when().get("/workers")
        .then().statusCode(200)
            .body("id", hasItem(id))
            .body("find { it.id == " + id + " }.isActive", equalTo(false));
    }

    @Test
    void listWorkers_isActiveTrueExcludesInactive() {
        int id = seedWorker("ZTESTW905", "Pedro", "Diaz", "Chofer", false);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("isActive", true)
        .when().get("/workers")
        .then().statusCode(200).body("id", not(hasItem(id)));
    }

    @Test
    void listWorkers_noFiltersIncludesSeeded() {
        int id = seedWorker("ZTESTW906", "Luis", "Vega", "Mecánico", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/workers")
        .then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    void listWorkers_orderedByFirstNameAsc() {
        int zeta = seedWorker("ZTESTW907", "Zzz", "Ztestord", "Chofer", true);
        int alfa = seedWorker("ZTESTW908", "Aaa", "Ztestord", "Chofer", true);
        String token = adminToken();

        // q acota el universo a los 2 sembrados (last_name comun); orden por first_name: Aaa antes que Zzz
        List<Integer> ids = given().header("Authorization", "Bearer " + token).queryParam("q", "ztestord")
        .when().get("/workers")
        .then().statusCode(200).extract().jsonPath().getList("id", Integer.class);

        assertTrue(ids.indexOf(alfa) < ids.indexOf(zeta),
            "Aaa (first_name) debe venir antes que Zzz; ids=" + ids);
    }

    // ---------- roles ------------------------------------------------------------

    @Test
    void listWorkers_withoutToken_returns401() {
        given().when().get("/workers").then().statusCode(401);
    }

    @Test
    void listWorkers_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");
        given().header("Authorization", "Bearer " + token).when().get("/workers")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void listWorkers_withDispatcherRole_returns403_COM003() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
        .when().get("/workers").then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @Test
    void listWorkers_withWarehouseKeeperRole_returns200() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
        .when().get("/workers").then().statusCode(200);
    }

    @Test
    void listWorkers_withOperationsManagerRole_returns200() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("om_test", "operations_manager"))
        .when().get("/workers").then().statusCode(200);
    }
}
