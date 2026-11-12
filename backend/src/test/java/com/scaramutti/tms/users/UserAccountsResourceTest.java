package com.scaramutti.tms.users;

import com.scaramutti.tms.support.RoutePolicyTrickyUrls;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.scaramutti.tms.support.ResponseHeaders.assertNotSent;
import static com.scaramutti.tms.support.ResponseHeaders.assertSentOnce;
import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * GET /users. Hermetico: las cuentas de prueba ({@code ztestuser}) van sobre trabajadores de prueba
 * ({@code ZTESTCTA}) y se borran antes y despues de cada caso. La tabla tiene cuentas que no son del
 * test, asi que las aserciones son por presencia de lo sembrado o contra lo que la tabla tiene en ese
 * momento, nunca por un tamano fijo.
 */
@QuarkusTest
class UserAccountsResourceTest {

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
        fixtures.deleteTestRoles();
    }

    // ---------- roles ------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"admin", "general_manager", "operations_manager", "finance_manager"})
    void listUserAccounts_forEachRoleThatAdministersAccounts_returns200WithTheAccounts(String role) {
        int accountId = seedAccount("ZTESTCTA01", "Lucia", "Ztlista", null, "sales", true, "ztestuserlistauno", true);

        listAs(role, null).then().statusCode(200).body("id", hasItem(accountId));
    }

    /** El rechazo, tambien el del HEAD, sale sin las cabeceras de cache: van en el exito, no en un filtro. */
    @ParameterizedTest
    @ValueSource(strings = {"sales", "dispatcher", "warehouse_keeper"})
    void listUserAccounts_forTheOtherRoles_returns403_COM003_withoutCacheHeaders(String role) {
        Response response = listAs(role, null);
        Response head = given().header("Authorization", "Bearer " + tokenFor(role)).when().head("/users");

        assertEquals(403, response.statusCode());
        assertEquals("COM-003", response.jsonPath().getString("code"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
        assertEquals(403, head.statusCode());
        assertNotSent(head, "Cache-Control", "Vary", "Pragma");
    }

    @Test
    void listUserAccounts_withoutToken_returns401() {
        given().when().get("/users").then().statusCode(401);
    }

    /** La ruta escrita torcida tampoco entra sin token, con las dos capas puestas como en produccion. */
    @Test
    void listUserAccounts_withTrickyUrls_withoutToken_returns401() {
        RoutePolicyTrickyUrls.assertAllReturn401WithoutToken("/users");
    }

    /** Lectura plana: quien tiene el menor de los cuatro cargos ve tambien las cuentas de admin y de gerencia. */
    @Test
    void listUserAccounts_asFinanceManager_includesTheAccountsOfAdminsAndManagers() {
        int adminAccountId = seedAccount(
            "ZTESTCTA02", "Marta", "Ztjefa", null, "admin", true, "ztestuserlistaadm", true);
        int managerAccountId = seedAccount(
            "ZTESTCTA11", "Raul", "Ztjefe", null, "general_manager", true, "ztestuserlistager", true);

        listAs("finance_manager", null).then().statusCode(200)
            .body("find { it.id == " + adminAccountId + " }.role.name", equalTo("admin"))
            .body("find { it.id == " + managerAccountId + " }.role.name", equalTo("general_manager"));
    }

    // ---------- cada fila ----------------------------------------------------------

    /**
     * Las claves exactas: sin el hash, sin la version de las sesiones y sin bloqueo. El nombre completo
     * une nombres, paterno y materno; el estado de la cuenta y el de su trabajador son dos datos. Antes
     * va un trabajador sin cuenta: en una base nueva los dos ids avanzan a la par, y un id cruzado
     * pasaria por bueno.
     */
    @Test
    void listUserAccounts_eachRow_bringsExactlyTheAccountItsWorkerAndItsRole() {
        fixtures.seedWorker("ZTESTCTA15", "Sin", "Ztcuenta", null, "sales", true);
        int workerId = fixtures.seedWorker("ZTESTCTA03", "Rosa", "Ztpaterno", "Ztmaterno", "sales", true);
        int accountId = fixtures.seedUserFor(workerId, "ztestuserlistafila", "sales", false);
        assertNotEquals(workerId, accountId, "precondicion: el id de la cuenta no es el de su trabajador");

        Map<String, Object> row = rowOf(listAs("admin", null), accountId);

        assertEquals(Set.of("id", "username", "worker", "role", "isActive"), row.keySet());
        assertEquals("ztestuserlistafila", row.get("username"));
        assertEquals(false, row.get("isActive"), "la cuenta esta apagada");
        assertEquals(Map.of("id", workerId, "fullName", "Rosa Ztpaterno Ztmaterno", "isActive", true),
            row.get("worker"), "su trabajador sigue activo");
        assertEquals(Set.of("name", "description", "level", "canLogin", "driverProfile"),
            ((Map<?, ?>) row.get("role")).keySet());
    }

    @Test
    void listUserAccounts_aWorkerWithoutMaternal_composesTheFullNameWithoutIt() {
        int accountId = seedAccount("ZTESTCTA04", "Ana", "Ztsolo", null, "sales", true, "ztestuserlistasola", true);

        listAs("admin", null).then().statusCode(200)
            .body("find { it.id == " + accountId + " }.worker.fullName", equalTo("Ana Ztsolo"));
    }

    /** El nombre puesto por SQL con espacios en los dos extremos sale recortado, como lo promete el contrato. */
    @Test
    void listUserAccounts_trimsTheEndsOfTheFullName() {
        int workerId = fixtures.seedWorker("ZTESTCTA12", "Ana", "Ztborde", null, "sales", true);
        int accountId = fixtures.seedUserFor(workerId, "ztestuserlistaborde", "sales");
        fixtures.setWorkerFirstName(workerId, "  Ana");
        fixtures.setWorkerMaternalLastName(workerId, "Ztmaterno  ");

        listAs("admin", null).then().statusCode(200)
            .body("find { it.id == " + accountId + " }.worker.fullName", equalTo("Ana Ztborde Ztmaterno"));
    }

    /**
     * Todas las cuentas, tambien la de un cargo que el catalogo ya no ofrece: la lista cuenta el cargo
     * que tiene el trabajador, y el catalogo de cargos es el que filtra por vigente.
     */
    @Test
    void listUserAccounts_includesTheAccountOfARetiredRole() {
        fixtures.seedRole("ztestrolecuenta", "ZTEST cargo retirado", 1, false, "NONE", false);
        int accountId = seedAccount(
            "ZTESTCTA14", "Olga", "Ztretiro", null, "ztestrolecuenta", true, "ztestuserlistaretiro", false);

        listAs("admin", null).then().statusCode(200)
            .body("find { it.id == " + accountId + " }.role.name", equalTo("ztestrolecuenta"));
    }

    /**
     * El cargo con la forma del catalogo de cargos, tambien la modalidad de ficha, que la lista lleva
     * como el texto de la columna: el mismo cargo tiene que salir igual por las dos rutas.
     */
    @ParameterizedTest
    @ValueSource(strings = {"assistant", "driver", "operations_manager"})
    void listUserAccounts_bringsTheRoleAsTheRoleCatalogDoes(String roleName) {
        int accountId = seedAccount(
            "ZTESTCTA05", "Jose", "Ztcargo", null, roleName, true, "ztestuserlistacargo", false);
        Map<String, Object> catalogRole = given().header("Authorization", "Bearer " + tokenFor("admin"))
            .when().get("/roles")
            .then().statusCode(200).extract().jsonPath().getMap("find { it.name == '" + roleName + "' }");

        assertEquals(catalogRole, rowOf(listAs("admin", null), accountId).get("role"));
    }

    // ---------- filtro ------------------------------------------------------------

    /**
     * El filtro es el estado de la CUENTA, no el de su trabajador: la apagada tiene el trabajador
     * activo, asi que un filtro por el trabajador la traeria entre las activas.
     */
    @Test
    void listUserAccounts_isActiveTrue_bringsOnlyTheActiveAccounts() {
        int active = seedAccount("ZTESTCTA06", "Eva", "Ztfiltro", null, "sales", true, "ztestuserlistaact", true);
        int inactive = seedAccount("ZTESTCTA07", "Ivan", "Ztfiltro", null, "sales", true, "ztestuserlistaina", false);

        listAs("admin", true).then().statusCode(200)
            .body("id", hasItem(active))
            .body("id", not(hasItem(inactive)))
            .body("isActive", not(hasItem(false)));
    }

    @Test
    void listUserAccounts_isActiveFalse_bringsOnlyTheInactiveAccounts() {
        int active = seedAccount("ZTESTCTA06", "Eva", "Ztfiltro", null, "sales", true, "ztestuserlistaact", true);
        int inactive = seedAccount("ZTESTCTA07", "Ivan", "Ztfiltro", null, "sales", true, "ztestuserlistaina", false);

        listAs("admin", false).then().statusCode(200)
            .body("id", hasItem(inactive))
            .body("id", not(hasItem(active)))
            .body("isActive", not(hasItem(true)));
    }

    @Test
    void listUserAccounts_withoutIsActive_bringsBoth() {
        int active = seedAccount("ZTESTCTA06", "Eva", "Ztfiltro", null, "sales", true, "ztestuserlistaact", true);
        int inactive = seedAccount("ZTESTCTA07", "Ivan", "Ztfiltro", null, "sales", true, "ztestuserlistaina", false);

        listAs("admin", null).then().statusCode(200).body("id", hasItems(active, inactive));
    }

    /**
     * Todas las cuentas de la tabla en cada filtro, cada una una vez: ninguna union deja una afuera ni
     * un tope corta lo que tiene la tabla. Se comparan como conjunto con ella; el orden tiene su caso.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {true, false})
    void listUserAccounts_bringsEveryAccountOfTheTable(Boolean isActive) {
        seedAccount("ZTESTCTA16", "Nora", "Zttodas", null, "sales", true, "ztestusertodasa", true);
        seedAccount("ZTESTCTA17", "Pia", "Zttodas", null, "driver", true, "ztestusertodasb", true);
        seedAccount("ZTESTCTA18", "Teo", "Zttodas", null, "sales", false, "ztestusertodasc", false);

        List<Integer> listed = listAs("admin", isActive).then().statusCode(200)
            .extract().jsonPath().getList("id", Integer.class);

        assertEquals(accountIdsInTheTable(isActive), Set.copyOf(listed));
        assertEquals(listed.size(), Set.copyOf(listed).size(), "cada cuenta una vez");
    }

    // ---------- orden -------------------------------------------------------------

    /**
     * Por nombre de usuario, y no por id, por el nombre del trabajador ni por estado: se siembran al
     * reves del orden esperado, con los nombres en un tercero y la apagada en el medio, con su
     * trabajador de baja. Los usuarios difieren solo en letras (la intercalacion de la base ordena
     * distinto los signos segun el entorno). Dos pedidos, el mismo orden.
     */
    @Test
    void listUserAccounts_areOrderedByUsername_andTheOrderIsStable() {
        seedAccount("ZTESTCTA08", "Beto", "Ztorden", null, "sales", true, "ztestuserordenc", true);
        int middle = seedAccount("ZTESTCTA09", "Alba", "Ztorden", null, "sales", false, "ztestuserordenb", false);
        seedAccount("ZTESTCTA10", "Ciro", "Ztorden", null, "sales", true, "ztestuserordena", true);

        Response response = listAs("admin", null);
        List<String> first = seededOrder(response);
        List<String> second = seededOrder(listAs("admin", null));

        assertEquals(List.of("ztestuserordena", "ztestuserordenb", "ztestuserordenc"), first);
        assertEquals(first, second);
        assertEquals(false, ((Map<?, ?>) rowOf(response, middle).get("worker")).get("isActive"),
            "su trabajador, de baja");
    }

    // ---------- cabeceras ---------------------------------------------------------

    /** El padron de accesos no lo guarda ninguna cache, y cada cabecera sale una vez. Sin Pragma. */
    @Test
    void listUserAccounts_sendsNoStoreAndVaryOnce() {
        Response response = listAs("admin", null);

        assertEquals(200, response.statusCode());
        assertSentOnce(response, "Cache-Control", "no-store");
        assertSentOnce(response, "Vary", "Authorization");
        assertNotSent(response, "Pragma");
    }

    /** El HEAD lo contesta el framework con el mismo metodo: las mismas cabeceras y el tipo del GET. */
    @Test
    void listUserAccounts_head_sendsNoStoreAndVaryOnceWithTheContentTypeOfTheGet() {
        String getContentType = listAs("admin", null).then().statusCode(200).extract().contentType();

        Response response = given().header("Authorization", "Bearer " + tokenFor("admin"))
        .when().head("/users");

        assertEquals(200, response.statusCode());
        assertEquals(getContentType, response.contentType());
        assertSentOnce(response, "Cache-Control", "no-store");
        assertSentOnce(response, "Vary", "Authorization");
        assertNotSent(response, "Pragma");
    }

    /** Nada lleva cuerpo: un GET con otro tipo de contenido no es un 415. */
    @Test
    void listUserAccounts_withAnotherContentType_returns200() {
        given().header("Authorization", "Bearer " + tokenFor("admin")).contentType("text/plain").body("x")
        .when().get("/users")
        .then().statusCode(200);
    }

    // ---------- fixtures ----------------------------------------------------------

    private int seedAccount(String document, String firstName, String paternalLastName, String maternalLastName,
            String roleName, boolean workerIsActive, String username, boolean accountIsActive) {
        int workerId = fixtures.seedWorker(document, firstName, paternalLastName, maternalLastName, roleName,
            workerIsActive);
        return fixtures.seedUserFor(workerId, username, roleName, accountIsActive);
    }

    /** Los ids de las cuentas de la tabla en ese filtro, leidos sin pasar por la consulta de la lista. */
    private Set<Integer> accountIdsInTheTable(Boolean isActive) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var query = entityManager.createNativeQuery(isActive == null
                ? "SELECT id FROM public.users" : "SELECT id FROM public.users WHERE is_active = ?1");
            if (isActive != null) {
                query.setParameter(1, isActive);
            }
            return ((List<?>) query.getResultList()).stream()
                .map(id -> ((Number) id).intValue()).collect(Collectors.toSet());
        });
    }

    /** Un token por cargo con un usuario que no se llama como el cargo. */
    private static String tokenFor(String role) {
        return fabricateAccessToken("ztest-" + role, role);
    }

    private static Response listAs(String role, Boolean isActive) {
        var request = given().header("Authorization", "Bearer " + tokenFor(role));
        if (isActive != null) {
            request.queryParam("isActive", isActive);
        }
        return request.when().get("/users");
    }

    private static Map<String, Object> rowOf(Response response, int accountId) {
        JsonPath body = response.then().statusCode(200).extract().jsonPath();
        return body.getMap("find { it.id == " + accountId + " }");
    }

    /** Los nombres de las cuentas sembradas para el orden, en el orden en que llegaron. */
    private static List<String> seededOrder(Response response) {
        return response.then().statusCode(200).extract().jsonPath().<String>getList("username").stream()
            .filter(username -> username.startsWith("ztestuserorden")).toList();
    }
}
