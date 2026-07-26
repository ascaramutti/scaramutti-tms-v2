package com.scaramutti.tms.warehouse.supplier;

import com.scaramutti.tms.shared.entity.Supplier;
import com.scaramutti.tms.shared.repository.SupplierRepository;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static com.scaramutti.tms.support.TestAuth.login;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests de GET/POST /warehouse/suppliers. Calco estructural de
 * ClientsResourceTest (paginacion, roles, duplicados de 2 campos), con el
 * bloque adicional de RN-WH14 (busqueda multi-palabra tokenizada) que NO
 * tiene precedente en ClientsResourceTest (client no tokeniza). La tabla
 * suppliers nace vacia (sin seeds) — todos los fixtures se siembran acá.
 */
@QuarkusTest
class WarehouseSuppliersResourceTest {

    @Inject SupplierRepository supplierRepository;
    @Inject WarehouseTestData fixtures;

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteWarehouseTestData());
    }

    /** Firma propia (con {@code ruc} explícito): la variante de support no lo setea. */
    private void seedSupplier(String name, String ruc, boolean isActive) {
        QuarkusTransaction.requiringNew().run(() -> {
            Supplier supplier = new Supplier();
            supplier.name = name;
            supplier.ruc = ruc;
            supplier.isActive = isActive;
            supplierRepository.persist(supplier);
        });
    }

    private String body(String name, String ruc, String phone, String contactName) {
        StringBuilder sb = new StringBuilder("{");
        if (name != null) sb.append("\"name\":\"").append(name).append("\",");
        if (ruc != null) sb.append("\"ruc\":\"").append(ruc).append("\",");
        if (phone != null) sb.append("\"phone\":\"").append(phone).append("\",");
        if (contactName != null) sb.append("\"contactName\":\"").append(contactName).append("\",");
        if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }

    // ---------- GET: shape / paginacion -----------------------------------------

    @Test
    void list_responseShape_matchesContract() {
        seedSupplier("ZTEST_Shape SAC", "99900000001", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?q=ZTEST_Shape")
        .then()
            .statusCode(200)
            .body("page", equalTo(0))
            .body("size", equalTo(20))
            .body("content[0].id", notNullValue())
            .body("content[0].name", notNullValue())
            .body("content[0].ruc", equalTo("99900000001"))
            .body("content[0].isActive", equalTo(true))
            .body("content[0].createdAt", notNullValue());
    }

    @Test
    void list_returnsApplicationJsonContentType() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers")
        .then()
            .statusCode(200)
            .contentType("application/json");
    }

    @Test
    void list_withoutQ_orderedByNameAscending() {
        seedSupplier("ZTEST_ZZZ Corp", null, true);
        seedSupplier("ZTEST_AAA Corp", null, true);
        seedSupplier("ZTEST_MMM Corp", null, true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?size=100")
        .then()
            .statusCode(200)
            .body("content.name", containsInRelativeOrder("ZTEST_AAA Corp", "ZTEST_MMM Corp", "ZTEST_ZZZ Corp"));
    }

    @Test
    void list_withIsActiveTrue_returnsOnlyActiveSuppliers() {
        seedSupplier("ZTEST_Activo SAC", null, true);
        seedSupplier("ZTEST_Inactivo SAC", null, false);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?q=ZTEST_&isActive=true")
        .then()
            .statusCode(200)
            .body("content.isActive", everyItem(equalTo(true)))
            .body("content.name", hasItem("ZTEST_Activo SAC"))
            .body("content.name", not(hasItem("ZTEST_Inactivo SAC")));
    }

    @Test
    void list_withCustomPageAndSize_returnsCorrectSlice() {
        for (int i = 0; i < 12; i++) {
            seedSupplier(String.format("ZTEST_Page%02d Corp", i), null, true);
        }
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?q=ZTEST_Page&page=1&size=5")
        .then()
            .statusCode(200)
            .body("page", equalTo(1))
            .body("size", equalTo(5))
            .body("totalElements", equalTo(12))
            .body("totalPages", equalTo(3))
            .body("content.size()", equalTo(5))
            .body("first", equalTo(false))
            .body("last", equalTo(false));
    }

    @Test
    void list_pageBeyondTotalPages_returnsEmptyContentAndLastTrue() {
        seedSupplier("ZTEST_Overflow SAC", null, true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?q=ZTEST_Overflow&page=99")
        .then()
            .statusCode(200)
            .body("content.size()", equalTo(0))
            .body("empty", equalTo(true))
            .body("last", equalTo(true));
    }

    @Test
    void list_withPageNegative_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?page=-1")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void list_withSizeAboveMax_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?size=101")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void list_withQTooShort_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?q=ab")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- GET: RN-WH14 — busqueda multi-palabra tokenizada ----------------

    @Test
    void list_withQCrossFieldWords_matchesSupplier_whenWordsSplitAcrossNameAndRuc() {
        // "Ferreteria" en el name, "77712" es substring del ruc — cada palabra
        // matchea en un campo DISTINTO, RN-WH14 exige que igual matcheen (AND de ORs).
        seedSupplier("ZTEST_Ferreteria Lima", "99977712345", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?q=Ferreteria 77712")
        .then()
            .statusCode(200)
            .body("content.name", hasItem("ZTEST_Ferreteria Lima"));
    }

    @Test
    void list_withQBothWordsInSameField_matches() {
        seedSupplier("ZTEST_Repuestos Diesel Norte", "99900000010", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?q=Repuestos Diesel")
        .then()
            .statusCode(200)
            .body("content.name", hasItem("ZTEST_Repuestos Diesel Norte"));
    }

    @Test
    void list_withQOneWordNotMatchingAnywhere_excludesSupplier() {
        seedSupplier("ZTEST_Repuestos Diesel Norte", "99900000011", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?q=Repuestos Inexistente999")
        .then()
            .statusCode(200)
            .body("content.name", not(hasItem("ZTEST_Repuestos Diesel Norte")));
    }

    @Test
    void list_withQAgainstNullRucSupplier_positiveControl_matchesViaNameOnly() {
        seedSupplier("ZTEST_Taller Mecanico Sur", null, true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?q=Taller Mecanico")
        .then()
            .statusCode(200)
            .body("content.name", hasItem("ZTEST_Taller Mecanico Sur"));
    }

    @Test
    void list_withQAgainstNullRucSupplier_negativeControl_noFalsePositiveNoSqlError() {
        // "Taller" matchea name, "55512" no matchea ni name ni ruc (ruc es null)
        // -> el AND falla, y sobre todo: ruc=null no debe romper la query (500).
        seedSupplier("ZTEST_Taller Mecanico Norte", null, true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?q=Taller 55512")
        .then()
            .statusCode(200)
            .body("content.name", not(hasItem("ZTEST_Taller Mecanico Norte")));
    }

    @Test
    void list_withQWordOrderReversed_returnsSameResultSet() {
        seedSupplier("ZTEST_Ferreteria Sur", "99977799999", true);
        String token = login("admin", "Admin1234");

        Integer idForward = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?q=Ferreteria 77799999")
        .then()
            .statusCode(200)
            .extract().jsonPath().getInt("content[0].id");

        Integer idReversed = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?q=77799999 Ferreteria")
        .then()
            .statusCode(200)
            .extract().jsonPath().getInt("content[0].id");

        org.junit.jupiter.api.Assertions.assertEquals(idForward, idReversed);
    }

    @Test
    void list_withQMultipleAndEdgeSpaces_doesNotBreakAndMatching() {
        seedSupplier("ZTEST_Repuestos Diesel Este", "99900000012", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .queryParam("q", "  Repuestos   Diesel  ")
        .when()
            .get("/warehouse/suppliers")
        .then()
            .statusCode(200)
            .body("content.name", hasItem("ZTEST_Repuestos Diesel Este"));
    }

    @Test
    void list_withQCaseInsensitiveMultiWord_matches() {
        seedSupplier("ZTEST_Repuestos Diesel Oeste", "99900000013", true);
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers?q=repuestos diesel")
        .then()
            .statusCode(200)
            .body("content.name", hasItem("ZTEST_Repuestos Diesel Oeste"));
    }

    // ---------- GET: auth / roles ------------------------------------------------

    @Test
    void list_withoutToken_returns401() {
        given()
        .when()
            .get("/warehouse/suppliers")
        .then()
            .statusCode(401);
    }

    @Test
    void list_withAdminRole_returns200() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withOperationsManagerRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("om_test", "operations_manager"))
        .when()
            .get("/warehouse/suppliers")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withFinanceManagerRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("fm_test", "finance_manager"))
        .when()
            .get("/warehouse/suppliers")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withWarehouseKeeperRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
        .when()
            .get("/warehouse/suppliers")
        .then()
            .statusCode(200);
    }

    @Test
    void list_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/warehouse/suppliers")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void list_withDispatcherRole_returns403_COM003() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
        .when()
            .get("/warehouse/suppliers")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    // ---------- POST: happy path ------------------------------------------------

    @Test
    void create_withFullPayload_returns201AndPersists() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_Repuestos Diesel SAC", "99911100011", "987654321", "Juan Ramos"))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(201)
            .contentType("application/json")
            .body("id", notNullValue())
            .body("name", equalTo("ZTEST_REPUESTOS DIESEL SAC"))
            .body("ruc", equalTo("99911100011"))
            .body("phone", equalTo("987654321"))
            .body("contactName", equalTo("Juan Ramos"))
            .body("isActive", equalTo(true))
            .body("createdAt", notNullValue());

        Supplier persisted = supplierRepository.find("name", "ZTEST_REPUESTOS DIESEL SAC").firstResult();
        assertNotNull(persisted);
    }

    @Test
    void create_withOnlyRequiredName_returns201WithNullOptionalFields() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_SoloNombreProv", null, null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(201)
            .body("ruc", nullValue())
            .body("phone", nullValue())
            .body("contactName", nullValue())
            .body("isActive", equalTo(true));
    }

    @Test
    void create_uppercasesName_sameCriteriaAsClients() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ztest_mixed case", null, null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(201)
            .body("name", equalTo("ZTEST_MIXED CASE"));
    }

    @Test
    void create_trimsWhitespaceInNameAndContactName() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("  ZTEST_Trim  ", null, null, "   "))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(201)
            .body("name", equalTo("ZTEST_TRIM"))
            .body("contactName", nullValue());
    }

    @Test
    void create_withTwoNullRucSuppliers_bothSucceed_noFalseConflict() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_NullRuc1", null, null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_NullRuc2", null, null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(201);
    }

    // ---------- POST: duplicados (WH-010) ---------------------------------------

    @Test
    void create_withDuplicateName_returns409_WH010() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_DupName", "99922200001", null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ztest_dupname", "99922200002", null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("WH-010"));
    }

    @Test
    void create_withDuplicateRuc_returns409_WH010() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_RucOwner", "99933300003", null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_RucChallenger", "99933300003", null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(409)
            .body("code", equalTo("WH-010"));
    }

    @Test
    void create_withDuplicateNameAndRuc_returns409_WH010() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_Both", "99944400004", null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_Both", "99944400004", null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(409)
            .body("code", equalTo("WH-010"));
    }

    // ---------- POST: validacion 400 (COM-001) -----------------------------------

    @Test
    void create_withMissingName_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{\"phone\":\"987654321\"}")
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withNameTooShort_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("AB", null, null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withInvalidRucPattern_letters_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_InvalidRuc", "2051234567A", null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withRucWrongLength_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_RucCorto", "2051234567", null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withInvalidPhonePattern_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_PhoneInvalido", null, "12345", null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    @Test
    void create_withEmptyBody_returns400_COM001() {
        String token = login("admin", "Admin1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    // ---------- POST: auth / roles -----------------------------------------------

    @Test
    void create_withoutToken_returns401() {
        given()
            .contentType(ContentType.JSON)
            .body(body("ZTEST_NoToken", null, null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(401);
    }

    @Test
    void create_withSalesRole_returns403_COM003() {
        String token = login("lcampos", "Sales1234");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body("ZTEST_Sales", null, null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withDispatcherRole_returns403_COM003() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("disp_test", "dispatcher"))
            .contentType(ContentType.JSON)
            .body(body("ZTEST_Dispatcher", null, null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    @Test
    void create_withWarehouseKeeperRole_returns201() {
        given()
            .header("Authorization", "Bearer " + fabricateAccessToken("wk_test", "warehouse_keeper"))
            .contentType(ContentType.JSON)
            .body(body("ZTEST_WarehouseKeeper", null, null, null))
        .when()
            .post("/warehouse/suppliers")
        .then()
            .statusCode(201);
    }
}
