package com.scaramutti.tms.workers;

import com.scaramutti.tms.support.WarehouseTestData;
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
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests de GET /document-types.
 *
 * <p>La tabla trae filas reales del padron, asi que ninguna asercion mira el tamano de la
 * lista: todas van por presencia o por ausencia del tipo sembrado.
 */
@QuarkusTest
class DocumentTypesResourceTest {

    @Inject WarehouseTestData fixtures;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestDocumentTypes();
    }

    @Test
    void list_returnsEachTypeWithTheShapeOfTheContract() {
        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/document-types")
        .then().statusCode(200)
            .contentType("application/json")
            .body("find { it.code == 'DNI' }.keySet()", containsInAnyOrder(
                "id", "code", "name", "maxLength", "validationPattern"));
    }

    /** El largo y el patron viajan porque son las reglas que la escritura aplica al numero. */
    @Test
    void list_carriesTheMaxLengthAndThePatternOfEachType() {
        fixtures.seedDocumentType("ZTDOC1", "ZTEST con patrón", 8, "^\\d{8}$", true);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/document-types")
        .then().statusCode(200)
            .body("find { it.code == 'ZTDOC1' }.maxLength", equalTo(8))
            .body("find { it.code == 'ZTDOC1' }.validationPattern", equalTo("^\\d{8}$"));
    }

    /** Un tipo sin patron manda la clave en nulo, no la omite: el cliente no la adivina. */
    @Test
    void list_typeWithoutPattern_travelsNull() {
        fixtures.seedDocumentType("ZTDOC2", "ZTEST sin patrón", 12, null, true);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/document-types")
        .then().statusCode(200)
            // La clave viaja PRESENTE en nulo. Afirmar solo que es nula no distingue eso de
            // que el cuerpo la omita, y omitirla obliga al cliente a adivinar.
            .body("find { it.code == 'ZTDOC2' }.keySet()", hasItem("validationPattern"))
            .body("find { it.code == 'ZTDOC2' }.validationPattern", nullValue());
    }

    /**
     * Un tipo retirado no se ofrece. Si apareciera, el formulario dejaria elegirlo y el alta
     * lo rebotaria con un error que quien lo eligio no puede entender.
     */
    @Test
    void list_omitsRetiredTypes() {
        fixtures.seedDocumentType("ZTDOC3", "ZTEST retirado", 8, null, false);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/document-types")
        .then().statusCode(200).body("code", not(hasItem("ZTDOC3")));
    }

    /**
     * El orden es por id ascendente, y se afirma sobre la lista ENTERA y no sobre dos
     * posiciones: la tabla es compartida y manana puede tener una fila mas en el medio.
     */
    @Test
    void list_isOrderedById() {
        fixtures.seedDocumentType("ZTDOC4", "ZTEST cuarto", 8, null, true);
        fixtures.seedDocumentType("ZTDOC5", "ZTEST quinto", 8, null, true);

        List<Integer> ids = given().header("Authorization", "Bearer " + adminToken())
        .when().get("/document-types")
        .then().statusCode(200).extract().jsonPath().getList("id", Integer.class);

        assertEquals(ids.stream().sorted().toList(), ids);
    }

    // ---------- quien puede leerlo -----------------------------------------------

    @Test
    void list_withoutToken_returns401() {
        given().when().get("/document-types").then().statusCode(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"warehouse_keeper", "sales", "dispatcher"})
    void list_withARoleOutsideTheFour_returns403(String role) {
        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-" + role, role))
        .when().get("/document-types")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "general_manager", "operations_manager", "finance_manager"})
    void list_withEachMaintenanceRole_returns200(String role) {
        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-" + role, role))
        .when().get("/document-types")
        .then().statusCode(200).body("code", hasItem("DNI"));
    }
}
