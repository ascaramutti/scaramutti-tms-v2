package com.scaramutti.tms.workers;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.scaramutti.tms.support.TestAuth.adminToken;
import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.login;
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

    @Inject WarehouseTestData fixtures;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    // ---------- happy path -------------------------------------------------------

    @Test
    void listWorkers_returnsSeededWorkerWithComposedFullName() {
        int id = fixtures.seedWorker("ZTESTW900", "Juan", "Perez", "operator", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/workers")
        .then().statusCode(200)
            .body("find { it.id == " + id + " }.fullName", equalTo("Juan Perez"))
            .body("find { it.id == " + id + " }.position", equalTo("Operador"))
            .body("find { it.id == " + id + " }.isActive", equalTo(true));
    }

    // ---------- q filter ---------------------------------------------------------

    @Test
    void listWorkers_qMatchesPartialNameCaseInsensitive() {
        int id = fixtures.seedWorker("ZTESTW902", "Carlos", "Ramirez", "driver", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("q", "carlos")
        .when().get("/workers")
        .then().statusCode(200)
            .body("id", hasItem(id))
            .body("find { it.id == " + id + " }.fullName", equalTo("Carlos Ramirez"));
    }

    @Test
    void listWorkers_qMultiWordMatchesFirstAndLastName() {
        int id = fixtures.seedWorker("ZTESTW903", "Juan", "Perez", "operator", true);
        String token = adminToken();

        // "juan perez": cada palabra matchea first_name O last_name (MultiWordSearch, AND de ORs)
        given().header("Authorization", "Bearer " + token).queryParam("q", "juan perez")
        .when().get("/workers")
        .then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    void listWorkers_qNoMatchReturnsEmptyArray() {
        fixtures.seedWorker("ZTESTW910", "Ana", "Silva", "assistant", true);
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
        int id = fixtures.seedWorker("ZTESTW904", "Ines", "Torres", "assistant", false);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("isActive", false)
        .when().get("/workers")
        .then().statusCode(200)
            .body("id", hasItem(id))
            .body("find { it.id == " + id + " }.isActive", equalTo(false));
    }

    @Test
    void listWorkers_isActiveTrueExcludesInactive() {
        int id = fixtures.seedWorker("ZTESTW905", "Pedro", "Diaz", "driver", false);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("isActive", true)
        .when().get("/workers")
        .then().statusCode(200).body("id", not(hasItem(id)));
    }

    @Test
    void listWorkers_qAndIsActiveCombined() {
        int active = fixtures.seedWorker("ZTESTW920", "Aaa", "Ztcombo", "driver", true);
        int inactive = fixtures.seedWorker("ZTESTW921", "Bbb", "Ztcombo", "driver", false);
        String token = adminToken();

        // q acota por apellido comun; isActive=true debe excluir al inactivo (AND de las 2 condiciones)
        given().header("Authorization", "Bearer " + token)
            .queryParam("q", "ztcombo").queryParam("isActive", true)
        .when().get("/workers")
        .then().statusCode(200)
            .body("id", hasItem(active))
            .body("id", not(hasItem(inactive)));
    }

    @Test
    void listWorkers_noFiltersIncludesSeeded() {
        int id = fixtures.seedWorker("ZTESTW906", "Luis", "Vega", "operator", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).when().get("/workers")
        .then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    void listWorkers_orderedByFirstNameAsc() {
        int zeta = fixtures.seedWorker("ZTESTW907", "Zzz", "Ztestord", "driver", true);
        int alfa = fixtures.seedWorker("ZTESTW908", "Aaa", "Ztestord", "driver", true);
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
        String token = login("sales", "Sales1234");
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
    // ---------- busqueda por numero de documento ---------------------------------

    @Test
    void listWorkers_qMatchesTheDocumentNumber() {
        int id = fixtures.seedWorker("ZTESTW940", "Ana", "Silva", "operator", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("q", "ZTESTW94")
        .when().get("/workers")
        .then().statusCode(200).body("id", hasItem(id));
    }

    /** Cada palabra puede caer en una columna distinta: una en el nombre y otra en el documento. */
    @Test
    void listWorkers_qMixesWordsAcrossNameAndDocument() {
        int id = fixtures.seedWorker("ZTESTW941", "Bruno", "Salas", "operator", true);
        String token = adminToken();

        given().header("Authorization", "Bearer " + token).queryParam("q", "bruno ZTESTW941")
        .when().get("/workers")
        .then().statusCode(200).body("id", hasItem(id));
    }

    /**
     * El encargado de almacen NO encuentra por documento. Ese numero no viaja en esta
     * respuesta, asi que dejarlo buscar permitiria confirmarlo probando prefijos: con tres
     * digitos por consulta, un documento de ocho sale en unas decenas de intentos.
     */
    @Test
    void listWorkers_qByDocument_asWarehouseKeeper_findsNothing() {
        int id = fixtures.seedWorker("ZTESTW942", "Clara", "Soto", "operator", true);

        given().header("Authorization", "Bearer "
                + fabricateAccessToken("ztest-wk", "warehouse_keeper"))
            .queryParam("q", "ZTESTW94")
        .when().get("/workers")
        .then().statusCode(200).body("id", not(hasItem(id)));
    }

    /**
     * El control del caso de arriba: la guarda cerro la columna del documento y NO rompio la
     * busqueda de siempre. Sin este par, "cerre la columna" y "rompi la busqueda" se ven igual.
     */
    @Test
    void listWorkers_qByName_asWarehouseKeeper_stillFinds() {
        int id = fixtures.seedWorker("ZTESTW943", "Damian", "Ztbusca", "operator", true);

        given().header("Authorization", "Bearer "
                + fabricateAccessToken("ztest-wk", "warehouse_keeper"))
            .queryParam("q", "Ztbusca")
        .when().get("/workers")
        .then().statusCode(200).body("id", hasItem(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "general_manager", "operations_manager", "finance_manager"})
    void listWorkers_qByDocument_forEachMaintenanceRole_finds(String role) {
        int id = fixtures.seedWorker("ZTESTW944", "Elsa", "Vera", "operator", true);

        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-" + role, role))
            .queryParam("q", "ZTESTW944")
        .when().get("/workers")
        .then().statusCode(200).body("id", hasItem(id));
    }

    /**
     * El parametro de busqueda VACIO equivale a omitirlo, y esto lo fija porque es
     * contraintuitivo: la validacion de largo minimo parece que deberia rechazarlo, y no lo
     * hace, porque un parametro de consulta vacio llega como nulo y esa validacion deja pasar
     * los nulos. Lo encontro la bateria por curl contra el servidor: al leer la anotacion, la
     * conclusion es la contraria. Medido ademas en clientes y en tipos de carga, que se
     * comportan igual.
     */
    @Test
    void listWorkers_withEmptyQ_behavesLikeOmittingIt() {
        int id = fixtures.seedWorker("ZTESTW950", "Fabio", "Ztvacio", "operator", true);
        String token = adminToken();

        // No se comparan tamanios entre dos peticiones: la tabla es compartida y cualquier
        // insercion en el medio voltearia el caso por algo que no es el codigo. Que aparezca
        // el trabajador sembrado ya prueba lo que el caso promete: el parametro vacio no filtra.
        given().header("Authorization", "Bearer " + token).queryParam("q", "")
        .when().get("/workers")
        .then().statusCode(200).body("id", hasItem(id));
    }

}
