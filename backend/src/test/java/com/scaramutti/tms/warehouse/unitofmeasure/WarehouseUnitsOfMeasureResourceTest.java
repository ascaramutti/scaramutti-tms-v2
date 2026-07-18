package com.scaramutti.tms.warehouse.unitofmeasure;

import com.scaramutti.tms.shared.entity.UnitOfMeasure;
import com.scaramutti.tms.shared.repository.UnitOfMeasureRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.login;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests de GET /warehouse/units-of-measure. Lista CERRADA
 * (RN-WH9): solo lectura, los 7 seeds de A1 (UND/LT/GAL/KG/MT/CJA/JGO) ya
 * estan en la BD de test. Calco de CurrenciesResourceTest (catalogo GET
 * simple sin paginar) + tests de rol (aca SI restringe, a diferencia de
 * currencies).
 */
@QuarkusTest
class WarehouseUnitsOfMeasureResourceTest {

    @Inject UnitOfMeasureRepository unitOfMeasureRepository;

    private static final String INACTIVE_TEST_CODE = "ZZZ";

    private void seedInactiveTestUnit() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (unitOfMeasureRepository.count("code", INACTIVE_TEST_CODE) > 0) {
                return;
            }
            UnitOfMeasure unit = new UnitOfMeasure();
            unit.code = INACTIVE_TEST_CODE;
            unit.name = "Unidad de test inactiva";
            unit.isActive = false;
            unitOfMeasureRepository.persist(unit);
        });
    }

    private void cleanupInactiveTestUnit() {
        QuarkusTransaction.requiringNew().run(() ->
            unitOfMeasureRepository.delete("code", INACTIVE_TEST_CODE)
        );
    }

    // ---------- Happy path / contrato -------------------------------------------

    @Test
    void list_seedUnits_includesAllSevenCodes() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/units-of-measure")
        .then()
            .statusCode(200)
            .body("size()", org.hamcrest.Matchers.greaterThanOrEqualTo(7))
            .body("code", hasItem("UND"))
            .body("code", hasItem("LT"))
            .body("code", hasItem("GAL"))
            .body("code", hasItem("KG"))
            .body("code", hasItem("MT"))
            .body("code", hasItem("CJA"))
            .body("code", hasItem("JGO"));
    }

    @Test
    void list_responseShape_matchesContract() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/units-of-measure")
        .then()
            .statusCode(200)
            .body("[0].id", notNullValue())
            .body("code", everyItem(matchesPattern("^.{1,10}$")))
            .body("[0].name", notNullValue())
            .body("[0].isActive", notNullValue());
    }

    @Test
    void list_returnsApplicationJsonContentType() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/units-of-measure")
        .then()
            .statusCode(200)
            .contentType("application/json");
    }

    @Test
    void list_resultsOrderedByCodeAscending() {
        String token = login("admin", "Admin1234");

        // Verifica el orden RELATIVO entre 2 codigos seed conocidos: CJA < UND.
        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/units-of-measure?isActive=true")
        .then()
            .statusCode(200)
            .body("code.findAll { it in ['CJA','UND'] }", org.hamcrest.Matchers.contains("CJA", "UND"));
    }

    // ---------- Filtro isActive ---------------------------------------------------

    @Test
    void list_withIsActiveTrue_excludesInactiveFixture() {
        seedInactiveTestUnit();
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/warehouse/units-of-measure?isActive=true")
            .then()
                .statusCode(200)
                .body("isActive", everyItem(equalTo(true)))
                .body("code", not(hasItem(INACTIVE_TEST_CODE)));
        } finally {
            cleanupInactiveTestUnit();
        }
    }

    @Test
    void list_withIsActiveFalse_returnsOnlyInactiveUnits() {
        seedInactiveTestUnit();
        try {
            String token = login("admin", "Admin1234");

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/warehouse/units-of-measure?isActive=false")
            .then()
                .statusCode(200)
                .body("isActive", everyItem(equalTo(false)))
                .body("code", hasItem(INACTIVE_TEST_CODE))
                .body("code", not(hasItem("UND")));
        } finally {
            cleanupInactiveTestUnit();
        }
    }

    // ---------- Auth / roles -------------------------------------------------------

    @Test
    void list_withoutToken_returns401() {
        given()
        .when()
            .get("/warehouse/units-of-measure")
        .then()
            .statusCode(401);
    }

    @Test
    void list_withAdminRole_returns200() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/units-of-measure")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withFinanceManagerRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("fm_test", "finance_manager"))
        .when()
            .get("/warehouse/units-of-measure")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withWarehouseKeeperRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
        .when()
            .get("/warehouse/units-of-measure")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/units-of-measure")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void list_withDispatcherRole_returns403_COM003() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
        .when()
            .get("/warehouse/units-of-measure")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    // ---------- Regresion: lista cerrada, SIN POST -----------------------------------

    @Test
    void post_toUnitsOfMeasure_isNotAllowed() {
        // RN-WH9: lista cerrada, sin creacion al vuelo. Regression guard: si
        // alguna vez se agrega un POST sin querer, este test lo cachea.
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{\"code\":\"XX\",\"name\":\"Test\"}")
        .when()
            .post("/warehouse/units-of-measure")
        .then()
            .statusCode(anyOf(equalTo(404), equalTo(405)));
    }
}
