package com.scaramutti.tms.users;

import com.scaramutti.tms.support.RoutePolicyTrickyUrls;
import com.scaramutti.tms.support.WarehouseTestData;
import com.scaramutti.tms.support.WarehouseTestData.ActorSeed;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.scaramutti.tms.support.ResponseHeaders.assertNotSent;
import static com.scaramutti.tms.support.ResponseHeaders.assertSentOnce;
import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /users/{id}. Hermetico como la lista: las cuentas de prueba ({@code ztestuser}) van sobre
 * trabajadores de prueba ({@code ZTEST}) y se borran antes y despues de cada caso.
 */
@QuarkusTest
class UserAccountDetailResourceTest {

    private static final String PASSWORD = "Sales1234";
    private static final String NEW_PASSWORD = "Nueva1234";

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
    void getUserAccount_forEachRoleThatAdministersAccounts_returns200(String role) {
        int accountId = seedAccount("ZTESTCTD01", "Lucia", "Ztdetalle", null, "sales", true, "ztestuserdetuno", true);

        getAs(role, accountId).then().statusCode(200).body("id", equalTo(accountId));
    }

    /** El rechazo, tambien el del HEAD, sale sin las cabeceras de cache: van en el exito, no en un filtro. */
    @ParameterizedTest
    @ValueSource(strings = {"sales", "dispatcher", "warehouse_keeper"})
    void getUserAccount_forTheOtherRoles_returns403_COM003_withoutCacheHeaders(String role) {
        int accountId = seedAccount("ZTESTCTD02", "Mario", "Ztdetalle", null, "sales", true, "ztestuserdetdos", true);

        Response response = getAs(role, accountId);
        Response head = given().header("Authorization", "Bearer " + tokenFor(role)).when().head("/users/" + accountId);

        assertEquals(403, response.statusCode());
        assertEquals("COM-003", response.jsonPath().getString("code"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
        assertEquals(403, head.statusCode());
        assertNotSent(head, "Cache-Control", "Vary", "Pragma");
    }

    /**
     * El cargo se decide antes de buscar o convertir el id: un cargo sin acceso recibe el mismo 403 con
     * un id inexistente, no numerico o fuera de rango, y no averigua por esta ruta cual existe.
     */
    @ParameterizedTest
    @ValueSource(strings = {"999999", "abc", "2147483648"})
    void getUserAccount_withRoleWithoutAccess_andAnyId_returns403NotA404(String id) {
        given().header("Authorization", "Bearer " + tokenFor("sales"))
        .when().get("/users/" + id)
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    /** Lectura plana: quien tiene el menor de los cuatro cargos lee tambien la cuenta de un admin. */
    @Test
    void getUserAccount_asFinanceManager_readsTheAccountOfAnAdmin() {
        int accountId = seedAccount("ZTESTCTD12", "Marta", "Ztjefa", null, "admin", true, "ztestuserdetadm", true);

        getAs("finance_manager", accountId).then().statusCode(200).body("role.name", equalTo("admin"));
    }

    @Test
    void getUserAccount_withoutToken_returns401() {
        given().when().get("/users/1").then().statusCode(401);
    }

    /** La ruta escrita torcida tampoco entra sin token, con las dos capas puestas como en produccion. */
    @Test
    void getUserAccount_withTrickyUrls_withoutToken_returns401() {
        RoutePolicyTrickyUrls.assertAllReturn401WithoutToken("/users/1");
    }

    // ---------- la cuenta ----------------------------------------------------------

    /**
     * Las claves exactas en cada nivel: sin el hash, sin la version de las sesiones, sin la marca de la
     * temporal y sin bloqueo. Antes va un trabajador sin cuenta, para que los ids de la cuenta y de su
     * trabajador no coincidan. Una cuenta vieja no tiene autores, y las dos claves viajan en nulo.
     */
    @Test
    void getUserAccount_anInactiveAccount_bringsExactlyTheAccountItsWorkerAndItsRole() {
        fixtures.seedWorker("ZTESTCTD03", "Sin", "Ztcuenta", null, "sales", true);
        int workerId = fixtures.seedWorker("ZTESTCTD04", "Rosa", "Ztpaterno", "Ztmaterno", "sales", true);
        int accountId = fixtures.seedUserFor(workerId, "ztestuserdetfila", "sales", false);
        assertNotEquals(workerId, accountId, "precondicion: el id de la cuenta no es el de su trabajador");

        Map<String, Object> body = getAs("admin", accountId).then().statusCode(200).extract().jsonPath().getMap("");

        assertEquals(Set.of("id", "username", "worker", "role", "isActive", "createdAt", "createdBy", "updatedAt",
            "updatedBy"), body.keySet());
        assertEquals(accountId, body.get("id"));
        assertEquals("ztestuserdetfila", body.get("username"));
        assertEquals(false, body.get("isActive"), "la cuenta esta apagada");
        assertEquals(Map.of("id", workerId, "fullName", "Rosa Ztpaterno Ztmaterno", "isActive", true,
            "firstName", "Rosa", "paternalLastName", "Ztpaterno", "maternalLastName", "Ztmaterno"),
            body.get("worker"), "su trabajador sigue activo");
        assertEquals(roleInTheTable("sales"), body.get("role"), "el cargo es la fila de public.roles");
        assertNull(body.get("createdBy"));
        assertNull(body.get("updatedBy"));
    }

    /**
     * Una cuenta activa de un trabajador dado de baja, de un cargo que inicia sesion: el estado de la
     * cuenta, el del trabajador y el ingreso del cargo no valen lo mismo, y fijar uno se ve.
     */
    @Test
    void getUserAccount_anActiveAccountOfAnInactiveWorker_bringsEachState() {
        int accountId = seedAccount("ZTESTCTD05", "Pia", "Ztactiva", null, "sales", false, "ztestuserdetactiva", true);

        getAs("admin", accountId).then().statusCode(200)
            .body("isActive", equalTo(true))
            .body("worker.isActive", equalTo(false))
            .body("role.canLogin", equalTo(true));
    }

    /** Sin materno, la clave viaja en nulo y no vacia, y el nombre completo no deja un espacio al final. */
    @Test
    void getUserAccount_aWorkerWithoutMaternal_bringsItNullAndComposesTheFullNameWithoutIt() {
        int accountId = seedAccount("ZTESTCTD06", "Ana", "Ztsolo", null, "sales", true, "ztestuserdetsola", true);

        Map<String, Object> worker = getAs("admin", accountId).then().statusCode(200)
            .extract().jsonPath().getMap("worker");

        assertTrue(worker.containsKey("maternalLastName"));
        assertNull(worker.get("maternalLastName"));
        assertEquals("Ana Ztsolo", worker.get("fullName"));
    }

    /** El usuario sale tal como lo guarda la base, sin pasarlo a minusculas. */
    @Test
    void getUserAccount_bringsTheUsernameAsStored() {
        int accountId = seedAccount("ZTESTCTD13", "Iris", "Ztmayus", null, "sales", true, "ztestuserDetMay", true);

        getAs("admin", accountId).then().statusCode(200).body("username", equalTo("ztestuserDetMay"));
    }

    /**
     * La cuenta de un cargo que el catalogo ya no ofrece tambien se lee: el detalle cuenta el cargo que
     * tiene el trabajador, y el catalogo de cargos es el que filtra por vigente.
     */
    @Test
    void getUserAccount_readsTheAccountOfARetiredRole() {
        fixtures.seedRole("ztestroledetalle", "ZTEST cargo retirado", 1, false, "NONE", false);
        int accountId = seedAccount(
            "ZTESTCTD14", "Olga", "Ztretiro", null, "ztestroledetalle", true, "ztestuserdetretiro", false);

        getAs("admin", accountId).then().statusCode(200).body("role.name", equalTo("ztestroledetalle"));
    }

    /**
     * El cargo y el trabajador salen igual que en la fila de la lista de la misma cuenta. El trabajador
     * esta activo y su cargo no inicia sesion: el estado y el ingreso no valen lo mismo, y un cruce se ve.
     */
    @Test
    void getUserAccount_bringsTheRoleAndTheWorkerAsTheListDoes() {
        int accountId = seedAccount(
            "ZTESTCTD07", "Teo", "Ztchofer", "Ztruta", "driver", true, "ztestuserdetchofer", false);

        Map<String, Object> detail = getAs("admin", accountId).then().statusCode(200).extract().jsonPath().getMap("");
        Map<String, Object> row = given().header("Authorization", "Bearer " + tokenFor("admin"))
            .when().get("/users").then().statusCode(200)
            .extract().jsonPath().getMap("find { it.id == " + accountId + " }");

        assertEquals(row.get("role"), detail.get("role"));
        Map<Object, Object> worker = new HashMap<>((Map<?, ?>) detail.get("worker"));
        worker.keySet().retainAll(Set.of("id", "fullName", "isActive"));
        assertEquals(row.get("worker"), worker);
    }

    /**
     * Quien la creo y quien la modifico son dos personas distintas, cada una con su usuario, su nombre y
     * el cargo de su trabajador; las dos marcas de tiempo, dos instantes distintos.
     */
    @Test
    void getUserAccount_bringsWhoCreatedAndWhoLastChangedTheAccount_andWhen() {
        ActorSeed creator = fixtures.seedActor("operations_manager", "ctdcrea");
        ActorSeed changer = fixtures.seedActor("finance_manager", "ctdcambia");
        int accountId = seedAccount("ZTESTCTD08", "Eva", "Ztautoria", null, "sales", true, "ztestuserdetautor", true);
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
        OffsetDateTime updatedAt = OffsetDateTime.of(2026, 6, 7, 8, 9, 10, 0, ZoneOffset.UTC);
        setAccountAuthorship(accountId, creator.userId(), createdAt, changer.userId(), updatedAt);

        Map<String, Object> body = getAs("admin", accountId).then().statusCode(200).extract().jsonPath().getMap("");

        assertEquals(Map.of("id", creator.userId(), "username", "ztestuserctdcrea",
            "fullName", "Actor operations manager", "position", "Gerente de Operaciones"), body.get("createdBy"));
        assertEquals(Map.of("id", changer.userId(), "username", "ztestuserctdcambia",
            "fullName", "Actor finance manager", "position", "Jefe de Finanzas"), body.get("updatedBy"));
        assertEquals(createdAt.toInstant(), OffsetDateTime.parse((String) body.get("createdAt")).toInstant());
        assertEquals(updatedAt.toInstant(), OffsetDateTime.parse((String) body.get("updatedAt")).toInstant());
    }

    /**
     * Una cuenta vieja que cambia su propia contrasena queda firmada por si misma: {@code updatedBy} deja
     * de ser nulo y {@code createdBy} sigue nulo. Ninguna lectura trae el hash, tampoco el nuevo.
     */
    @Test
    void getUserAccount_afterTheOwnPasswordChange_isSignedByTheAccount_andNeverBringsTheHash() {
        int accountId = seedAccount("ZTESTCTD09", "Ivo", "Ztpropio", null, "sales", true, "ztestuserdetpropio", true);
        fixtures.copyPasswordHashFrom(accountId, "sales");
        given().header("Authorization", "Bearer " + login("ztestuserdetpropio", PASSWORD))
            .contentType(ContentType.JSON)
            .body("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
        .when().post("/auth/change-password")
        .then().statusCode(200);

        Response response = getAs("admin", accountId);

        assertEquals(200, response.statusCode());
        assertEquals(accountId, response.jsonPath().getInt("updatedBy.id"));
        assertEquals("ztestuserdetpropio", response.jsonPath().getString("updatedBy.username"));
        assertNull(response.jsonPath().get("createdBy"));
        String body = response.asString();
        String hash = passwordHashOf(accountId);
        assertFalse(body.contains(hash), "el cuerpo no trae el hash");
        assertFalse(body.contains(hash.substring(7)), "ni su parte sin el prefijo del algoritmo");
    }

    // ---------- 404 ------------------------------------------------------------------

    @Test
    void getUserAccount_withAnIdThatDoesNotExist_returns404_USR001_withoutCacheHeaders() {
        Response response = getAs("admin", 999999);

        assertEquals(404, response.statusCode());
        assertEquals("application/problem+json", response.contentType());
        assertEquals("USR-001", response.jsonPath().getString("code"));
        assertEquals("Not Found", response.jsonPath().getString("title"));
        assertEquals("urn:tms:error:usr-001", response.jsonPath().getString("type"));
        assertEquals("Cuenta no encontrada", response.jsonPath().getString("detail"));
        assertNotSent(response, "Cache-Control", "Vary", "Pragma");
    }

    /** El conversor del parametro falla antes del recurso: 404 y sin cuerpo, tambien fuera de int32. */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "2147483648", "9999999999999999999999"})
    void getUserAccount_withAnIdThatIsNotAnInt32_returns404WithoutBody(String id) {
        given().header("Authorization", "Bearer " + tokenFor("admin"))
        .when().get("/users/" + id)
        .then().statusCode(404).body(equalTo(""));
    }

    // ---------- cabeceras -----------------------------------------------------------

    @Test
    void getUserAccount_sendsNoStoreAndVaryOnce() {
        int accountId = seedAccount("ZTESTCTD10", "Gil", "Ztcache", null, "sales", true, "ztestuserdetcache", true);

        Response response = getAs("admin", accountId);

        assertEquals(200, response.statusCode());
        assertSentOnce(response, "Cache-Control", "no-store");
        assertSentOnce(response, "Vary", "Authorization");
        assertNotSent(response, "Pragma");
    }

    @Test
    void getUserAccount_head_sendsNoStoreAndVaryOnceWithTheContentTypeOfTheGet() {
        int accountId = seedAccount("ZTESTCTD11", "Noe", "Zthead", null, "sales", true, "ztestuserdethead", true);
        String getContentType = getAs("admin", accountId).then().statusCode(200).extract().contentType();

        Response response = given().header("Authorization", "Bearer " + tokenFor("admin"))
        .when().head("/users/" + accountId);

        assertEquals(200, response.statusCode());
        assertEquals(getContentType, response.contentType());
        assertSentOnce(response, "Cache-Control", "no-store");
        assertSentOnce(response, "Vary", "Authorization");
        assertNotSent(response, "Pragma");
    }

    // ---------- fixtures ----------------------------------------------------------

    private int seedAccount(String document, String firstName, String paternalLastName, String maternalLastName,
            String roleName, boolean workerIsActive, String username, boolean accountIsActive) {
        int workerId = fixtures.seedWorker(document, firstName, paternalLastName, maternalLastName, roleName,
            workerIsActive);
        return fixtures.seedUserFor(workerId, username, roleName, accountIsActive);
    }

    private void setAccountAuthorship(int accountId, int createdBy, OffsetDateTime createdAt, int updatedBy,
            OffsetDateTime updatedAt) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "UPDATE public.users SET created_by = ?1, created_at = ?2, updated_by = ?3, updated_at = ?4 "
                    + "WHERE id = ?5")
            .setParameter(1, createdBy).setParameter(2, createdAt).setParameter(3, updatedBy)
            .setParameter(4, updatedAt).setParameter(5, accountId).executeUpdate());
    }

    /** El cargo tal como esta en la tabla, con las claves de la respuesta. */
    private Map<String, Object> roleInTheTable(String name) {
        Object[] row = QuarkusTransaction.requiringNew().call(() -> (Object[]) entityManager.createNativeQuery(
                "SELECT name, description, level, can_login, driver_profile FROM public.roles WHERE name = ?1")
            .setParameter(1, name).getSingleResult());
        return Map.of("name", row[0], "description", row[1], "level", ((Number) row[2]).intValue(),
            "canLogin", row[3], "driverProfile", row[4]);
    }

    private String passwordHashOf(int accountId) {
        return QuarkusTransaction.requiringNew().call(() -> (String) entityManager.createNativeQuery(
            "SELECT password_hash FROM public.users WHERE id = ?1").setParameter(1, accountId).getSingleResult());
    }

    private static String login(String username, String password) {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
            .when().post("/auth/login")
            .then().statusCode(200).extract().path("token");
    }

    /** Un token por cargo con un usuario que no se llama como el cargo. */
    private static String tokenFor(String role) {
        return fabricateAccessToken("ztest-" + role, role);
    }

    private static Response getAs(String role, Object id) {
        return given().header("Authorization", "Bearer " + tokenFor(role)).when().get("/users/" + id);
    }
}
