package com.scaramutti.tms.workers;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.scaramutti.tms.support.TestAuth.adminToken;
import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests de GET /roles.
 *
 * <p>La tabla de las once filas se escribe DOS veces en la suite: una mide lo que dejo la
 * migracion, leyendo la base, y esta mide lo que sale por el cable. Con una sola, un mapper
 * que invente valores pasa igual; con las dos, hay que romper las dos.
 */
@QuarkusTest
class RolesResourceTest {

    @Inject WarehouseTestData fixtures;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestRoles();
    }

    @ParameterizedTest(name = "{0} sale como {1}, nivel {2}")
    @CsvSource({
        "admin,              Administrador del Sistema,  4, true,  NONE",
        "general_manager,    Gerente General,            3, true,  NONE",
        "operations_manager, Gerente de Operaciones,     3, true,  NONE",
        "finance_manager,    Jefe de Finanzas,           2, true,  NONE",
        "dispatcher,         Coordinador de Operaciones, 2, true,  NONE",
        "sales,              Ejecutivo de Ventas,        2, true,  NONE",
        "warehouse_keeper,   Encargado de Almacén,       1, true,  NONE",
        "driver,             Conductor,                  1, false, REQUIRED",
        "escort,             Escolta,                    1, false, REQUIRED",
        "assistant,          Ayudante,                   1, false, OPTIONAL",
        "operator,           Operador,                   1, false, NONE",
    })
    void list_carriesEachRoleOfTheOrganigram(
            String name, String description, int level, boolean canLogin, String driverProfile) {
        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/roles")
        .then().statusCode(200)
            .body("find { it.name == '" + name + "' }.description", equalTo(description))
            .body("find { it.name == '" + name + "' }.level", equalTo(level))
            .body("find { it.name == '" + name + "' }.canLogin", equalTo(canLogin))
            .body("find { it.name == '" + name + "' }.driverProfile", equalTo(driverProfile));
    }

    @Test
    void list_returnsEachRoleWithTheShapeOfTheContract() {
        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/roles")
        .then().statusCode(200)
            .contentType("application/json")
            .body("find { it.name == 'admin' }.keySet()", containsInAnyOrder(
                "name", "description", "level", "canLogin", "driverProfile"));
    }

    /**
     * Se lee como el organigrama, de arriba abajo, y a igual nivel en el orden en que los
     * roles entraron. Se afirma sobre la lista ENTERA y no sobre dos posiciones: asi sigue
     * valiendo el dia que se sume un rol.
     */
    @Test
    void list_isOrderedByLevelDescending() {
        List<Integer> levels = given().header("Authorization", "Bearer " + adminToken())
        .when().get("/roles")
        .then().statusCode(200).extract().jsonPath().getList("level", Integer.class);

        List<Integer> descending = levels.stream()
            .sorted(java.util.Comparator.reverseOrder()).toList();
        assertEquals(descending, levels);
        assertTrue(levels.size() >= 11, "el organigrama tiene al menos sus once filas");
    }

    /** Un rol retirado no se ofrece: se retira poniendolo inactivo y deja de asignarse. */
    @Test
    void list_omitsRetiredRoles() {
        fixtures.seedRole("ztestrole9", "ZTEST retirado", 1, false, "NONE", false);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/roles")
        .then().statusCode(200).body("name", not(hasItem("ztestrole9")));
    }

    /**
     * El catalogo NO se recorta segun quien pregunta. Recortarlo en el servidor obligaria al
     * catalogo a saber quien lo pide, y el detalle igual necesita mostrar cargos que quien
     * mira no puede asignar. Quien decide eso es la regla de las escrituras.
     */
    @Test
    void list_doesNotFilterByTheLevelOfWhoAsks() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-fm", "finance_manager"))
        .when().get("/roles")
        .then().statusCode(200).body("name", hasItem("admin"));
    }

    /**
     * Tampoco se recorta por si el rol inicia sesion. Los dos criterios son independientes:
     * en el nivel 1 conviven cargos que nunca inician sesion y el encargado de almacen, que
     * si lo hace.
     */
    @Test
    void list_doesNotFilterByWhetherTheRoleCanLogIn() {
        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/roles")
        .then().statusCode(200)
            .body("name", hasItem("driver"))
            .body("name", hasItem("operator"));
    }

    // ---------- quien puede leerlo -----------------------------------------------

    @Test
    void list_withoutToken_returns401() {
        given().when().get("/roles").then().statusCode(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"warehouse_keeper", "sales", "dispatcher"})
    void list_withARoleOutsideTheFour_returns403(String role) {
        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-" + role, role))
        .when().get("/roles")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "general_manager", "operations_manager", "finance_manager"})
    void list_withEachMaintenanceRole_returns200(String role) {
        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-" + role, role))
        .when().get("/roles")
        .then().statusCode(200).body("name", hasItem("admin"));
    }
    /**
     * A igual nivel manda el id: el que entro antes va antes. Se mide con dos roles sembrados
     * porque el objeto de respuesta no publica el id, y el orden de los once del organigrama
     * viene del alta de la base y no de esta consulta. Sin este caso, declarar el sentido de
     * ordenamiento encadenado al final (que lo aplica a las DOS columnas y deja el id al
     * reves) pasa en verde, y es justo el error que el repositorio documenta.
     */
    @Test
    void list_atTheSameLevel_isOrderedByIdAscending() {
        fixtures.seedRole("ztestrolea", "ZTEST primero", 3, false, "NONE", true);
        fixtures.seedRole("ztestroleb", "ZTEST segundo", 3, false, "NONE", true);

        List<String> names = given().header("Authorization", "Bearer " + adminToken())
        .when().get("/roles").then().statusCode(200)
            .extract().jsonPath().getList("name", String.class);

        assertTrue(names.indexOf("ztestrolea") < names.indexOf("ztestroleb"), "names=" + names);
    }

}
