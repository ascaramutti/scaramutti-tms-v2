package com.scaramutti.tms.workers;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static com.scaramutti.tms.support.TestAuth.adminToken;
import static com.scaramutti.tms.support.TestAuth.fabricateTokenForUser;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests de PUT /workers/{id}.
 *
 * <p>Limpia ANTES y DESPUES de cada caso, igual que el alta.
 *
 * <p>DOS REGLAS QUE ESTA SUITE NO NEGOCIA, y que salieron caras en el PR anterior:
 *
 * <p>1. Toda respuesta que no sea 2xx afirma el CODIGO, nunca solo el numero. En esta operacion
 * conviven tres codigos distintos bajo 403 y siete bajo 400: un caso que mire el numero puede
 * pasar por el motivo equivocado, con la regla que dice medir sin ejecutarse nunca.
 *
 * <p>2. Todo actor va SEMBRADO y su token anclado a su usuario. Con el sujeto fijo de un token
 * fabricado, la regla de rango no encuentra a su actor y devuelve el mismo 403 que la lista de
 * roles: dos guardas distintas, indistinguibles.
 */
@QuarkusTest
class WorkerUpdateResourceTest {

    @Inject WarehouseTestData fixtures;
    @Inject DataSource dataSource;

    private Integer documentTypeId;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
        fixtures.deleteTestRoles();
        fixtures.deleteTestDocumentTypes();
        documentTypeId = null;
    }

    /** Tipo ancho y sin formato: los casos que no miden el tipo no dependen del padron real. */
    private int documentTypeId() {
        if (documentTypeId == null) {
            documentTypeId = fixtures.seedDocumentType("ZTDOC00", "ZTEST generico", 20, null, true);
        }
        return documentTypeId;
    }

    private io.restassured.specification.RequestSpecification put(String token) {
        return given().header("Authorization", "Bearer " + token).contentType("application/json");
    }

    /** El cuerpo completo, que es lo que el endpoint espera: es un reemplazo, no un parche. */
    private String body(String firstName, String lastName, String documentNumber, String phone,
            String role, String hireDate, String driverJson, String reason) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"firstName\":\"").append(firstName).append("\",");
        json.append("\"lastName\":\"").append(lastName).append("\",");
        json.append("\"documentTypeId\":").append(documentTypeId()).append(',');
        json.append("\"documentNumber\":\"").append(documentNumber).append("\",");
        if (phone != null) json.append("\"phone\":\"").append(phone).append("\",");
        json.append("\"role\":\"").append(role).append("\",");
        json.append("\"hireDate\":\"").append(hireDate).append('"');
        if (driverJson != null) json.append(",\"driver\":").append(driverJson);
        if (reason != null) json.append(",\"reason\":\"").append(reason).append('"');
        return json.append('}').toString();
    }

    /** Un trabajador sembrado con los mismos literales que despues se reenvian sin cambios. */
    private int seedWorker(String documentNumber, String role) {
        int id = fixtures.seedWorker(documentNumber, "Juan", "Pérez", role, true);
        fixtures.setWorkerDocumentType(id, documentTypeId());
        fixtures.setWorkerHireDate(id, LocalDate.of(2024, 3, 1));
        return id;
    }

    /** El cuerpo que reenvia EXACTAMENTE lo sembrado. El oraculo son los literales, no un GET. */
    private String unchangedBody(String documentNumber, String role) {
        return body("Juan", "Pérez", documentNumber, null, role, "2024-03-01", null, null);
    }

    private List<String> auditFieldNames(int workerId) {
        return fixtures.workerAuditRows(workerId).stream()
            .map(WarehouseTestData.WorkerAuditRow::fieldName).toList();
    }

    // ---------- el reemplazo, que es lo que distingue este endpoint de un parche ----------

    @Test
    void update_withTheFullBody_returns200WithTheFifteenKeys() {
        int id = seedWorker("ZTESTE001", "operator");

        put(adminToken()).body(body("Ana", "Silva", "ZTESTE001", "987654321", "operator",
                "2025-01-15", null, null))
        .when().put("/workers/" + id)
        .then().statusCode(200)
            .body("keySet()", containsInAnyOrder(
                "id", "firstName", "lastName", "documentType", "documentNumber", "phone",
                "role", "hireDate", "isActive", "createdAt", "createdBy", "updatedAt",
                "updatedBy", "driver", "hasUser"))
            .body("id", equalTo(id))
            .body("firstName", equalTo("Ana"))
            .body("lastName", equalTo("Silva"))
            .body("phone", equalTo("987654321"))
            .body("hireDate", equalTo("2025-01-15"));
    }

    /**
     * ES UN REEMPLAZO: omitir el telefono lo BORRA.
     *
     * <p>Es la mutacion mas probable de todo el endpoint, porque el instinto al escribirlo es
     * "si no vino, no lo toco". Se mira la FILA y no solo la respuesta, y se afirma la fila de
     * auditoria: borrar un dato tiene que dejar rastro igual que cambiarlo.
     */
    @Test
    void update_omittingThePhone_clearsIt() {
        int id = seedWorker("ZTESTE003", "operator");
        fixtures.setWorkerPhone(id, "987654321");

        put(adminToken()).body(unchangedBody("ZTESTE003", "operator"))
        .when().put("/workers/" + id)
        .then().statusCode(200).body("phone", nullValue());

        assertNull(fixtures.workerRowOf(id).phone(), "omitir el telefono lo borra de la fila");
        assertEquals(List.of("phone"), auditFieldNames(id));
        assertEquals("987654321", fixtures.workerAuditRows(id).get(0).oldValue());
        assertNull(fixtures.workerAuditRows(id).get(0).newValue());
    }

    /** Gemelo del anterior: el formulario manda la clave en nulo en vez de omitirla. */
    @Test
    void update_withPhoneExplicitlyNull_alsoClearsIt() {
        int id = seedWorker("ZTESTE004", "operator");
        fixtures.setWorkerPhone(id, "987654321");

        put(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,"documentNumber":"ZTESTE004",
             "phone":null,"role":"operator","hireDate":"2024-03-01"}""".formatted(documentTypeId()))
        .when().put("/workers/" + id)
        .then().statusCode(200).body("phone", nullValue());

        assertNull(fixtures.workerRowOf(id).phone());
    }

    @Test
    void update_anInactiveWorker_isEditable() {
        int id = fixtures.seedWorker("ZTESTE007", "Juan", "Pérez", "operator", false);
        fixtures.setWorkerDocumentType(id, documentTypeId());
        fixtures.setWorkerHireDate(id, LocalDate.of(2024, 3, 1));

        put(adminToken()).body(body("Ana", "Pérez", "ZTESTE007", null, "operator", "2024-03-01", null, null))
        .when().put("/workers/" + id)
        .then().statusCode(200)
            .body("firstName", equalTo("Ana"))
            .body("isActive", equalTo(false));

        assertTrue(!fixtures.workerRowOf(id).isActive(), "editar no reactiva");
    }

    @Test
    void update_theResponse_isTheSameBodyAsTheDetail() {
        int id = seedWorker("ZTESTE002", "operator");

        var updated = put(adminToken())
            .body(body("Ana", "Silva", "ZTESTE002", null, "operator", "2024-03-01", null, null))
            .when().put("/workers/" + id).then().statusCode(200)
            .extract().body().jsonPath().getMap("$");

        var read = given().header("Authorization", "Bearer " + adminToken())
            .when().get("/workers/" + id).then().statusCode(200)
            .extract().body().jsonPath().getMap("$");

        assertEquals(updated, read, "el 200 y el detalle tienen que ser el MISMO cuerpo");
    }

    // ---------- unicidad contra UNO MISMO: el caso que mas se rompe ----------

    /**
     * Reenviar el propio numero de documento sin cambios responde bien, no como conflicto.
     *
     * <p>Este caso NO puede distinguir "el chequeo se excluyo a si mismo" de "no hay chequeo":
     * si el chequeo desapareciera, no habria violacion porque el valor no cambia. Lo que SI mata
     * es el chequeo que NO se excluye, que es el defecto real. La distincion fina la miden los
     * casos del repositorio, llamando al metodo.
     *
     * <p>La lista exacta de campos auditados mata ademas otro defecto: comparar el documento
     * contra nulo en vez de contra lo guardado escribiria una fila de un cambio que no hubo.
     */
    @Test
    void update_withItsOwnDocumentNumber_returns200() {
        int id = seedWorker("ZTESTE010", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE010", "987654321", "operator",
                "2024-03-01", null, null))
        .when().put("/workers/" + id)
        .then().statusCode(200).body("documentNumber", equalTo("ZTESTE010"));

        assertEquals(List.of("phone"), auditFieldNames(id),
            "reenviar el propio documento no es un cambio");
    }

    @Test
    void update_withTheDocumentOfAnotherWorker_returns409_WRK002() {
        int id = seedWorker("ZTESTE011", "operator");
        fixtures.seedWorker("ZTESTE012", "Ana", "Silva", "operator", true);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE012", null, "operator",
                "2024-03-01", null, "Correccion del documento mal digitado"))
        .when().put("/workers/" + id)
        .then().statusCode(409).body("code", equalTo("WRK-002"));

        assertEquals("ZTESTE011", fixtures.workerRowOf(id).documentNumber(), "la fila no se movio");
        assertEquals(List.of(), auditFieldNames(id));
    }

    /** Dar de baja a alguien no libera su numero de documento. */
    @Test
    void update_withTheDocumentOfAnInactiveWorker_returns409_WRK002() {
        int id = seedWorker("ZTESTE013", "operator");
        fixtures.seedWorker("ZTESTE014", "Ana", "Silva", "operator", false);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE014", null, "operator",
                "2024-03-01", null, "Correccion del documento mal digitado"))
        .when().put("/workers/" + id)
        .then().statusCode(409).body("code", equalTo("WRK-002"));
    }

    /**
     * Reenviar la propia licencia responde bien Y conserva LA MISMA fila.
     *
     * <p>El id de la ficha importa: operaciones lo guarda en sus asignaciones, asi que borrar y
     * recrear la ficha rompe viajes ya asignados sin que ningun otro caso lo vea.
     */
    @Test
    void update_withItsOwnLicense_returns200_andKeepsTheSameProfileRow() {
        int id = seedWorker("ZTESTE015", "driver");
        int profileId = fixtures.seedDriverProfileFor(id, "ZTESTL015", "A-IIIc",
            WarehouseTestData.STATUS_AVAILABLE, true);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE015", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL015\",\"licenseCategory\":\"A-IIb\"}", null))
        .when().put("/workers/" + id)
        .then().statusCode(200).body("driver.licenseCategory", equalTo("A-IIb"));

        assertEquals(profileId, fixtures.driverRowOf(id).id(), "la ficha NO se recrea");
    }

    @Test
    void update_withTheLicenseOfAnotherProfile_returns409_WRK007() {
        int id = seedWorker("ZTESTE016", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL016", null, WarehouseTestData.STATUS_AVAILABLE, true);
        int other = fixtures.seedWorker("ZTESTE017", "Ana", "Silva", "driver", true);
        fixtures.seedDriverProfileFor(other, "ZTESTL017", null, WarehouseTestData.STATUS_AVAILABLE, false);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE016", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL017\"}", null))
        .when().put("/workers/" + id)
        .then().statusCode(409).body("code", equalTo("WRK-007"));

        assertEquals("ZTESTL016", fixtures.driverRowOf(id).licenseNumber(), "la propia no se movio");
    }

    // ---------- el motivo: obligatorio SOLO cuando el documento cambia ----------

    @Test
    void update_changingTheDocument_withoutReason_returns400_WRK009() {
        int id = seedWorker("ZTESTE020", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE021", null, "operator", "2024-03-01", null, null))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-009"));

        assertEquals("ZTESTE020", fixtures.workerRowOf(id).documentNumber());
        assertEquals(List.of(), auditFieldNames(id));
    }

    /** El par toma el borde por los dos lados: ninguno de los dos solo distingue el limite. */
    @Test
    void update_changingTheDocument_withANineCharacterReason_returns400_WRK009() {
        int id = seedWorker("ZTESTE022", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE023", null, "operator", "2024-03-01", null, "123456789"))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-009"));
    }

    @Test
    void update_changingTheDocument_withATenCharacterReason_returns200() {
        int id = seedWorker("ZTESTE024", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE025", null, "operator", "2024-03-01", null, "1234567890"))
        .when().put("/workers/" + id)
        .then().statusCode(200).body("documentNumber", equalTo("ZTESTE025"));
    }

    /** Diez espacios no son una justificacion: el motivo se recorta antes de medirlo. */
    @Test
    void update_changingTheDocument_withABlankReason_returns400_WRK009() {
        int id = seedWorker("ZTESTE026", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE027", null, "operator", "2024-03-01", null, "          "))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-009"));
    }

    @Test
    void update_changingOnlyThePhone_withoutReason_returns200() {
        int id = seedWorker("ZTESTE028", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE028", "987654321", "operator", "2024-03-01", null, null))
        .when().put("/workers/" + id)
        .then().statusCode(200);
    }

    /**
     * ESTE es el caso que obliga a que el minimo del motivo viva en el servicio y no en el borde.
     *
     * <p>Sin el documento cambiando, el motivo es opcional y libre: un motivo corto tiene que
     * pasar. Con el minimo declarado en la forma del cuerpo, este caso saldria como error de
     * validacion y el contrato quedaria roto en silencio.
     */
    @Test
    void update_withoutChangingTheDocument_aShortReasonIsAccepted() {
        int id = seedWorker("ZTESTE029", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE029", "987654321", "operator",
                "2024-03-01", null, "corto"))
        .when().put("/workers/" + id)
        .then().statusCode(200);

        assertEquals("corto", fixtures.workerAuditRows(id).get(0).reason());
    }

    @Test
    void update_theReason_landsOnEveryChangedRow() {
        int id = seedWorker("ZTESTE030", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE030", "987654321", "operator",
                "2025-06-01", null, "Ajuste pedido por la gerencia"))
        .when().put("/workers/" + id)
        .then().statusCode(200);

        var rows = fixtures.workerAuditRows(id);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(r -> "Ajuste pedido por la gerencia".equals(r.reason())),
            "el motivo va en TODAS las filas de esa edicion, no solo en una");
    }

    @Test
    void update_withAReasonAndNoChange_writesNothing() {
        int id = seedWorker("ZTESTE031", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE031", null, "operator",
                "2024-03-01", null, "Un motivo por si solo no es un cambio"))
        .when().put("/workers/" + id)
        .then().statusCode(200);

        assertEquals(List.of(), auditFieldNames(id));
    }


    // ---------- auditoria: una fila por campo cambiado, ninguna por los demas ----------

    /**
     * Los siete campos cambiados dejan SIETE filas, con etiqueta y los dos valores.
     *
     * <p>Se afirman las listas ENTERAS y ordenadas, no que "hubo cambios": que un campo se caiga
     * del diff significa que deja de guardarse EN SILENCIO, con 200, y solo la lista completa lo
     * ve. Los valores de los dos lados matan ademas intercambiar viejo por nuevo.
     */
    @Test
    void update_changingEveryField_leavesOneRowPerField_withLabelAndBothValues() {
        int id = seedWorker("ZTESTE040", "operator");
        fixtures.setWorkerPhone(id, "987654321");
        int otherType = fixtures.seedDocumentType("ZTDOC91", "ZTEST otro", 20, null, true);

        put(adminToken()).body("""
            {"firstName":"Ana","lastName":"Silva","documentTypeId":%d,"documentNumber":"ZTESTE041",
             "phone":"912345678","role":"dispatcher","hireDate":"2025-06-01",
             "reason":"Correccion integral pedida por la gerencia"}""".formatted(otherType))
        .when().put("/workers/" + id)
        .then().statusCode(200);

        var rows = fixtures.workerAuditRows(id);
        assertEquals(List.of("firstName", "lastName", "documentType", "documentNumber", "phone",
            "role", "hireDate"), rows.stream().map(WarehouseTestData.WorkerAuditRow::fieldName).toList());
        assertEquals(List.of("Nombre", "Apellido", "Tipo de documento", "Número de documento",
            "Teléfono", "Cargo", "Fecha de ingreso"),
            rows.stream().map(WarehouseTestData.WorkerAuditRow::fieldLabel).toList());
        assertEquals(List.of("Juan", "Pérez", "ZTDOC00", "ZTESTE040", "987654321", "operator",
            "2024-03-01"), rows.stream().map(WarehouseTestData.WorkerAuditRow::oldValue).toList());
        assertEquals(List.of("Ana", "Silva", "ZTDOC91", "ZTESTE041", "912345678", "dispatcher",
            "2025-06-01"), rows.stream().map(WarehouseTestData.WorkerAuditRow::newValue).toList());
        assertTrue(rows.stream().allMatch(r -> "FIELD_EDIT".equals(r.changeType())));
    }

    /**
     * Seis campos reenviados iguales y uno distinto dejan UNA fila.
     *
     * <p>Es el que mata quitar la comparacion que decide si hubo cambio: sin ella la lista pasa
     * de uno a siete.
     */
    @Test
    void update_changingOnlyOneField_leavesExactlyOneRow() {
        int id = seedWorker("ZTESTE042", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE042", "987654321", "operator",
                "2024-03-01", null, null))
        .when().put("/workers/" + id)
        .then().statusCode(200);

        assertEquals(List.of("phone"), auditFieldNames(id));
    }

    /** El cuerpo sale de los LITERALES del fixture, no de un GET: el oraculo no se copia. */
    @Test
    void update_withoutAnyChange_writesNoAuditRow() {
        int id = seedWorker("ZTESTE043", "operator");

        put(adminToken()).body(unchangedBody("ZTESTE043", "operator"))
        .when().put("/workers/" + id)
        .then().statusCode(200);

        assertEquals(List.of(), auditFieldNames(id));
    }

    /** Cambiar solo la caja ES un cambio: comparar ignorando mayusculas lo perderia. */
    @Test
    void update_changingOnlyTheCase_isAChange() {
        int id = seedWorker("ZTESTE044", "operator");

        put(adminToken()).body(body("juan", "Pérez", "ZTESTE044", null, "operator", "2024-03-01", null, null))
        .when().put("/workers/" + id)
        .then().statusCode(200);

        assertEquals(List.of("firstName"), auditFieldNames(id));
    }

    /**
     * El cargo se guarda por su NOMBRE DE SISTEMA, no por el visible.
     *
     * <p>El nombre visible lo cambia una migracion: ya pasó con uno de los cargos en este mismo
     * modulo. Si el historial guardara ese, pasaria a afirmar un cambio que nunca ocurrio.
     */
    @Test
    void update_changingTheRole_auditsTheSystemName_notTheVisibleOne() {
        int id = seedWorker("ZTESTE045", "operator");

        put(adminToken()).body(unchangedBody("ZTESTE045", "dispatcher"))
        .when().put("/workers/" + id)
        .then().statusCode(200);

        var row = fixtures.workerAuditRows(id).get(0);
        assertEquals("operator", row.oldValue());
        assertEquals("dispatcher", row.newValue());
    }

    @Test
    void update_theAuditAuthorIsTheSessionUser() {
        var actor = fixtures.seedActor("general_manager", "70");
        int id = seedWorker("ZTESTE046", "operator");

        put(fabricateTokenForUser(actor.userId(), "ztestuser70", "general_manager"))
            .body(body("Ana", "Pérez", "ZTESTE046", null, "operator", "2024-03-01", null, null))
        .when().put("/workers/" + id).then().statusCode(200);

        assertEquals(actor.userId(), fixtures.workerAuditRows(id).get(0).changedBy());
    }

    // ---------- lo que el cuerpo no mueve ----------

    /**
     * El trabajador se siembra INACTIVO y con otro autor a proposito: con uno activo y sin autor,
     * "no cambio" y "el cuerpo se aplico" darian lo mismo y la asercion no podria fallar.
     */
    @Test
    void update_withServerOwnedFieldsInTheBody_ignoresThem() {
        var otherActor = fixtures.seedActor("finance_manager", "71");
        var actor = fixtures.seedActor("general_manager", "72");
        int id = fixtures.seedWorker("ZTESTE047", "Juan", "Pérez", "operator", false);
        fixtures.setWorkerDocumentType(id, documentTypeId());
        fixtures.setWorkerHireDate(id, LocalDate.of(2024, 3, 1));
        fixtures.setWorkerAudit(id, otherActor.userId(), otherActor.userId());

        put(fabricateTokenForUser(actor.userId(), "ztestuser72", "general_manager")).body("""
            {"id":999999,"firstName":"Ana","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTESTE047","role":"operator","hireDate":"2024-03-01",
             "isActive":true,"hasUser":true,"createdAt":"2000-01-01T00:00:00Z",
             "createdBy":{"id":1},"updatedBy":{"id":1}}""".formatted(documentTypeId()))
        .when().put("/workers/" + id)
        .then().statusCode(200)
            .body("id", equalTo(id))
            .body("isActive", equalTo(false))
            .body("createdBy.id", equalTo(otherActor.userId()))
            .body("updatedBy.id", equalTo(actor.userId()))
            .body("hasUser", equalTo(false))
            .body("createdAt", not(org.hamcrest.Matchers.startsWith("2000")));
    }

    /** Un PUT que no cambia nada igual mueve la marca y la firma: es la regla publicada. */
    @Test
    void update_withoutAnyChange_stillMovesUpdatedAtAndUpdatedBy() {
        var actor = fixtures.seedActor("general_manager", "73");
        int id = seedWorker("ZTESTE048", "operator");
        var before = fixtures.workerRowOf(id);

        put(fabricateTokenForUser(actor.userId(), "ztestuser73", "general_manager"))
            .body(unchangedBody("ZTESTE048", "operator"))
        .when().put("/workers/" + id).then().statusCode(200);

        var after = fixtures.workerRowOf(id);
        assertNotEquals(before.updatedAt(), after.updatedAt(), "la marca se mueve igual");
        assertEquals(actor.userId(), after.updatedBy());
        assertEquals(before.createdAt(), after.createdAt(), "la de creacion no");
        assertEquals(List.of(), auditFieldNames(id), "pero no deja rastro de un cambio que no hubo");
    }


    // ---------- rango: sobre el cargo ACTUAL y sobre el NUEVO, distinguibles ----------

    /**
     * El cargo ACTUAL fuera de alcance frena, aunque el del cuerpo este dentro.
     *
     * <p>Los dos casos de rango estan CRUZADOS a proposito. El caso ingenuo —actor y objetivo del
     * mismo nivel, cuerpo con el mismo cargo— no sirve: si se borrara uno de los dos chequeos, el
     * otro devolveria el mismo codigo y el caso seguiria en verde con la guarda rota.
     */
    @Test
    void update_theCurrentRoleRank_usesTheStoredRole_notTheBodyOne() {
        var actor = fixtures.seedActor("finance_manager", "80");
        int id = seedWorker("ZTESTE050", "sales");

        put(fabricateTokenForUser(actor.userId(), "ztestuser80", "finance_manager"))
            .body(unchangedBody("ZTESTE050", "driver"))
        .when().put("/workers/" + id)
        .then().statusCode(403).body("code", equalTo("WRK-006"));

        assertEquals(List.of(), auditFieldNames(id));
    }

    /** Y al reves: el cargo NUEVO fuera de alcance frena aunque el guardado este dentro. */
    @Test
    void update_theNewRoleRank_usesTheBodyRole_notTheStoredOne() {
        var actor = fixtures.seedActor("finance_manager", "81");
        int id = seedWorker("ZTESTE051", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL051", null, WarehouseTestData.STATUS_AVAILABLE, true);

        put(fabricateTokenForUser(actor.userId(), "ztestuser81", "finance_manager"))
            .body(unchangedBody("ZTESTE051", "sales"))
        .when().put("/workers/" + id)
        .then().statusCode(403).body("code", equalTo("WRK-006"));
    }

    /** Control positivo: sin el, "siempre 403" pasaria los dos casos de arriba. */
    @Test
    void update_withBothRolesInRank_returns200() {
        var actor = fixtures.seedActor("finance_manager", "82");
        int id = seedWorker("ZTESTE052", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL052", null, WarehouseTestData.STATUS_AVAILABLE, true);

        put(fabricateTokenForUser(actor.userId(), "ztestuser82", "finance_manager"))
            .body(unchangedBody("ZTESTE052", "assistant"))
        .when().put("/workers/" + id).then().statusCode(200);
    }

    /** El chequeo del cargo actual va ANTES de resolver el nuevo: por eso gana a un cargo inexistente. */
    @Test
    void order_theCurrentRoleRankBeatsAnInvalidRole() {
        var actor = fixtures.seedActor("finance_manager", "83");
        int id = seedWorker("ZTESTE053", "sales");

        put(fabricateTokenForUser(actor.userId(), "ztestuser83", "finance_manager"))
            .body(unchangedBody("ZTESTE053", "no_existe"))
        .when().put("/workers/" + id)
        .then().statusCode(403).body("code", equalTo("WRK-006"));
    }

    @Test
    void order_anInvalidRoleBeatsTheNewRoleRank() {
        var actor = fixtures.seedActor("finance_manager", "84");
        int id = seedWorker("ZTESTE054", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL054", null, WarehouseTestData.STATUS_AVAILABLE, true);

        put(fabricateTokenForUser(actor.userId(), "ztestuser84", "finance_manager"))
            .body(unchangedBody("ZTESTE054", "no_existe"))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-005"));
    }

    /** La exencion del administrador sale de la FILA, no del grupo del token. */
    @Test
    void update_theAdminExemption_comesFromTheDatabase_notFromTheToken() {
        var actor = fixtures.seedActor("general_manager", "85");
        int id = seedWorker("ZTESTE055", "operations_manager");

        put(fabricateTokenForUser(actor.userId(), "ztestuser85", "admin"))
            .body(unchangedBody("ZTESTE055", "operations_manager"))
        .when().put("/workers/" + id)
        .then().statusCode(403).body("code", equalTo("WRK-006"));
    }

    /** Los dos motivos de rango dan el MISMO cuerpo: no se deduce nada de la jerarquia ajena. */
    @Test
    void update_theRankForbiddenBody_isIdenticalForBothTriggers() {
        var actor = fixtures.seedActor("finance_manager", "86");
        String token = fabricateTokenForUser(actor.userId(), "ztestuser86", "finance_manager");
        int outOfRankCurrent = seedWorker("ZTESTE056", "sales");
        int inRank = seedWorker("ZTESTE057", "driver");
        fixtures.seedDriverProfileFor(inRank, "ZTESTL057", null, WarehouseTestData.STATUS_AVAILABLE, true);

        String byCurrent = put(token).body(unchangedBody("ZTESTE056", "driver"))
            .when().put("/workers/" + outOfRankCurrent).then().statusCode(403)
            .body("code", equalTo("WRK-006")).extract().asString();
        String byNew = put(token).body(unchangedBody("ZTESTE057", "sales"))
            .when().put("/workers/" + inRank).then().statusCode(403)
            .body("code", equalTo("WRK-006")).extract().asString();

        assertEquals(withoutRequestSpecifics(byCurrent), withoutRequestSpecifics(byNew),
            "los dos motivos de rango tienen que dar el MISMO cuerpo");
    }

    /**
     * Borra lo que cambia por pedido y no por motivo: el identificador de traza y la ruta del
     * recurso. Lo que queda es lo unico que puede filtrar algo de la jerarquia ajena.
     */
    private String withoutRequestSpecifics(String problemBody) {
        return problemBody
            .replaceAll("\"traceId\":\"[^\"]*\"", "\"traceId\":\"X\"")
            .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"X\"");
    }

    // ---------- nadie se cambia su propio cargo ----------

    /** Un administrador SI edita el resto de su propia ficha. */
    @Test
    void update_admin_editsItsOwnRecord_returns200() {
        var actor = fixtures.seedActor("admin", "90");
        fixtures.setWorkerDocumentType(actor.workerId(), documentTypeId());
        fixtures.setWorkerHireDate(actor.workerId(), LocalDate.of(2024, 3, 1));

        put(fabricateTokenForUser(actor.userId(), "ztestuser90", "admin"))
            .body(body("Actor", "admin", "ZTESTA90", "987654321", "admin", "2024-03-01", null, null))
        .when().put("/workers/" + actor.workerId())
        .then().statusCode(200).body("phone", equalTo("987654321"));
    }

    /**
     * Pero NO su propio cargo. Es la unica guarda contra que el ultimo administrador se degrade a
     * si mismo y deje a la empresa sin administradores por esta API: el rango no lo cubre porque
     * el administrador esta exento de el.
     */
    @Test
    void update_admin_changingItsOwnRole_returns403_WRK012() {
        var actor = fixtures.seedActor("admin", "91");
        fixtures.setWorkerDocumentType(actor.workerId(), documentTypeId());
        fixtures.setWorkerHireDate(actor.workerId(), LocalDate.of(2024, 3, 1));

        put(fabricateTokenForUser(actor.userId(), "ztestuser91", "admin"))
            .body(body("Actor", "admin", "ZTESTA91", null, "general_manager", "2024-03-01", null, null))
        .when().put("/workers/" + actor.workerId())
        .then().statusCode(403).body("code", equalTo("WRK-012"));

        assertEquals("admin", fixtures.userRoleNameOf(actor.userId()), "el usuario no se movio");
        assertEquals(List.of(), auditFieldNames(actor.workerId()));
    }

    /**
     * Para quien no es administrador, el rango corta ANTES y el codigo es otro.
     *
     * <p>Afirmar el codigo es lo unico que separa este caso del de arriba: los dos son 403.
     */
    @Test
    void update_aNonAdminChangingItsOwnRole_returns403_WRK006_notWRK012() {
        var actor = fixtures.seedActor("general_manager", "92");
        fixtures.setWorkerDocumentType(actor.workerId(), documentTypeId());
        fixtures.setWorkerHireDate(actor.workerId(), LocalDate.of(2024, 3, 1));

        put(fabricateTokenForUser(actor.userId(), "ztestuser92", "general_manager"))
            .body(body("Actor", "general_manager", "ZTESTA92", null, "finance_manager", "2024-03-01", null, null))
        .when().put("/workers/" + actor.workerId())
        .then().statusCode(403).body("code", equalTo("WRK-006"));
    }

    // ---------- el cargo y la cuenta ----------

    @Test
    void update_changingTheRoleOfAWorkerWithUser_alsoChangesTheUserRole() {
        var actor = fixtures.seedActor("general_manager", "93");
        int id = seedWorker("ZTESTE060", "sales");
        int userId = fixtures.seedUserFor(id, "ztestuser94", "sales");

        put(fabricateTokenForUser(actor.userId(), "ztestuser93", "general_manager"))
            .body(unchangedBody("ZTESTE060", "finance_manager"))
        .when().put("/workers/" + id).then().statusCode(200);

        assertEquals("finance_manager", fixtures.userRoleNameOf(userId),
            "el cargo y el rol de su cuenta son la misma decision");
        assertEquals(List.of("role", "user.role"), auditFieldNames(id));
        // Los DOS valores: sin esto, invertir los argumentos deja el historial diciendo que la
        // cuenta fue al reves, y ningun caso se entera.
        var rastroDeLaCuenta = fixtures.workerAuditRows(id).get(1);
        assertEquals("sales", rastroDeLaCuenta.oldValue());
        assertEquals("finance_manager", rastroDeLaCuenta.newValue());
        assertEquals("Rol del usuario", rastroDeLaCuenta.fieldLabel());
    }

    /** Sin usuario, una sola fila: escribir siempre la del usuario seria una mentira. */
    @Test
    void update_changingTheRoleOfAWorkerWithoutUser_leavesOnlyOneRow() {
        var actor = fixtures.seedActor("general_manager", "95");
        int id = seedWorker("ZTESTE061", "sales");

        put(fabricateTokenForUser(actor.userId(), "ztestuser95", "general_manager"))
            .body(unchangedBody("ZTESTE061", "finance_manager"))
        .when().put("/workers/" + id).then().statusCode(200);

        assertEquals(List.of("role"), auditFieldNames(id));
    }

    /**
     * Una cuenta no puede quedar con un cargo que no inicia sesion.
     *
     * <p>Fija ademas el orden: el cuerpo no manda ficha y el cargo destino la EXIGE, asi que si
     * este chequeo fuera despues del de la ficha saldria el otro codigo.
     */
    @Test
    void update_toARoleThatCannotLogin_onAWorkerWithUser_returns400_WRK011() {
        int id = seedWorker("ZTESTE062", "sales");
        int userId = fixtures.seedUserFor(id, "ztestuser96", "sales");

        put(adminToken()).body(unchangedBody("ZTESTE062", "driver"))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-011"));

        assertEquals("sales", fixtures.userRoleNameOf(userId));
        assertEquals(List.of(), auditFieldNames(id));
    }

    /** El mismo cambio SIN usuario pasa: el par mata "se dispara siempre" y "nunca". */
    @Test
    void update_toARoleThatCannotLogin_onAWorkerWithoutUser_returns200() {
        int id = seedWorker("ZTESTE063", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE063", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL063\"}", null))
        .when().put("/workers/" + id).then().statusCode(200);

        assertNotNull(fixtures.driverRowOf(id));
    }

    /**
     * Una cuenta APAGADA sigue contando.
     *
     * <p>Filtrarla dejaria armada justo la cuenta rota que la regla previene: cuando el modulo de
     * usuarios la vuelva a encender, tendria un cargo sin permisos.
     */
    @Test
    void update_anInactiveUserStillCounts_returns400_WRK011() {
        int id = seedWorker("ZTESTE064", "sales");
        int userId = fixtures.seedUserFor(id, "ztestuser97", "sales");
        fixtures.setUserActive(userId, false);

        put(adminToken()).body(unchangedBody("ZTESTE064", "driver"))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-011"));
    }

    /** Reenviar el MISMO cargo no dispara la regla, aunque ese cargo no inicie sesion. */
    @Test
    void update_resendingTheSameRoleThatCannotLogin_returns200() {
        int id = seedWorker("ZTESTE065", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL065", null, WarehouseTestData.STATUS_AVAILABLE, true);
        fixtures.seedUserFor(id, "ztestuser98", "driver");

        put(adminToken()).body(body("Ana", "Pérez", "ZTESTE065", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL065\"}", null))
        .when().put("/workers/" + id).then().statusCode(200);
    }


    // ---------- las transiciones de la ficha que tocan la FILA ----------

    /**
     * Las celdas de la matriz que ademas escriben en la base.
     *
     * <p>La matriz ENTERA (dieciocho combinaciones) se mide como funcion pura, en microsegundos.
     * Aca quedan solo las que tienen que verse en la fila: que el id sobreviva, que apagar no
     * borre, y que rechazar no deje nada.
     */
    @ParameterizedTest(name = "ficha {0}/{1} + cargo {2} {3} -> {4} {5}")
    @CsvSource({
        // estado previo, activa, cargo nuevo, ficha en el cuerpo, status, codigo
        "existe, si, driver,    con, 200, ",
        "existe, si, operator,  sin, 200, ",
        "existe, si, operator,  con, 400, WRK-008",
        "existe, si, assistant, sin, 200, ",
        "existe, no, driver,    con, 200, ",
        "existe, no, driver,    sin, 400, WRK-008",
        "existe, no, assistant, sin, 200, ",
        "falta,  no, driver,    con, 200, ",
        "falta,  no, assistant, sin, 200, ",
    })
    void update_coversTheProfileTransitionsThatTouchTheRow(String previous, String rowActive,
            String newRole, String sendsProfile, int expectedStatus, String expectedCode) {
        String documentNumber = "ZTESTE1" + Math.abs((previous + rowActive + newRole + sendsProfile).hashCode() % 100000);
        int id = seedWorker(documentNumber, "driver".equals(newRole) || "existe".equals(previous) ? "driver" : "operator");
        Integer profileId = null;
        if ("existe".equals(previous)) {
            profileId = fixtures.seedDriverProfileFor(id, "ZTESTLM" + id, "A-IIIc",
                WarehouseTestData.STATUS_AVAILABLE, "si".equals(rowActive));
        }
        String driverJson = "con".equals(sendsProfile)
            ? "{\"licenseNumber\":\"ZTESTLN" + id + "\"}" : null;

        var response = put(adminToken()).body(body("Juan", "Pérez", documentNumber, null, newRole,
                "2024-03-01", driverJson, null))
            .when().put("/workers/" + id).then().statusCode(expectedStatus);
        // El CODIGO y no solo el numero: bajo 400 conviven siete codigos distintos en esta
        // operacion, asi que el numero solo no dice cual guarda respondio.
        if (expectedCode != null) {
            response.body("code", equalTo(expectedCode));
        }

        var row = fixtures.driverRowOf(id);
        if (expectedStatus == 400) {
            assertNotNull(row, "un rechazo no puede borrar la ficha que ya estaba");
            assertEquals("ZTESTLM" + id, row.licenseNumber(), "ni tocarla");
            return;
        }
        if (profileId != null) {
            assertNotNull(row, "la ficha NUNCA se borra, solo se apaga");
            assertEquals(profileId, row.id(), "y conserva su id: operaciones la referencia");
        } else if ("con".equals(sendsProfile)) {
            // El camino de CREACION: antes este bloque no se ejecutaba nunca y la rama quedaba
            // sin ninguna asercion.
            assertNotNull(row, "el cargo que exige ficha y no la tenia, la crea");
            assertEquals("ZTESTLN" + id, row.licenseNumber());
        } else {
            assertNull(row, "sin fila previa y sin ficha en el cuerpo no se crea ninguna");
        }
    }

    /**
     * El id de la ficha sobrevive el viaje de ida y vuelta.
     *
     * <p>Borrar y recrear rompe los viajes ya asignados, que guardan ese id. Ningun otro caso lo
     * veria: la respuesta se veria igual de bien.
     */
    @Test
    void update_theProfileRow_survivesTheRoundTripWithItsId() {
        int id = seedWorker("ZTESTE070", "driver");
        int profileId = fixtures.seedDriverProfileFor(id, "ZTESTL070", null,
            WarehouseTestData.STATUS_AVAILABLE, true);

        put(adminToken()).body(unchangedBody("ZTESTE070", "operator"))
            .when().put("/workers/" + id).then().statusCode(200);
        assertEquals(profileId, fixtures.driverRowOf(id).id());
        assertTrue(!fixtures.driverRowOf(id).isActive(), "apagada, no borrada");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE070", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL071\"}", null))
            .when().put("/workers/" + id).then().statusCode(200);

        var row = fixtures.driverRowOf(id);
        assertEquals(profileId, row.id(), "la MISMA fila vuelve a encenderse");
        assertTrue(row.isActive());
        assertEquals("ZTESTL071", row.licenseNumber(), "con los datos nuevos");
    }

    /** El estado ausente CONSERVA el guardado: en el alta significa lo contrario. */
    @Test
    void update_withoutStatus_keepsTheStoredOne() {
        int id = seedWorker("ZTESTE072", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL072", null,
            WarehouseTestData.STATUS_MAINTENANCE, true);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE072", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL072\"}", null))
        .when().put("/workers/" + id)
        .then().statusCode(200).body("driver.status", equalTo("MAINTENANCE"));
    }

    /** Su gemelo: sin el, "siempre conservar" pasaria. */
    @Test
    void update_withAnExplicitStatus_changesIt() {
        int id = seedWorker("ZTESTE073", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL073", null,
            WarehouseTestData.STATUS_MAINTENANCE, true);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE073", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL073\",\"status\":\"NOT_AVAILABLE\"}", null))
        .when().put("/workers/" + id)
        .then().statusCode(200).body("driver.status", equalTo("NOT_AVAILABLE"));

        assertEquals(List.of("driver.status"), auditFieldNames(id));
    }

    /**
     * Editar a un trabajador DADO DE BAJA no le enciende la ficha.
     *
     * <p>Sin esta guarda, editar a alguien que se fue lo devuelve al catalogo de conductores y
     * vuelve a ser asignable a un viaje, sin pasar por la reactivacion, que es el unico camino
     * que deberia encenderla.
     */
    @Test
    void update_theProfileOfAnInactiveWorker_staysOff() {
        int id = fixtures.seedWorker("ZTESTE074", "Juan", "Pérez", "driver", false);
        fixtures.setWorkerDocumentType(id, documentTypeId());
        fixtures.setWorkerHireDate(id, LocalDate.of(2024, 3, 1));
        fixtures.seedDriverProfileFor(id, "ZTESTL074", null, WarehouseTestData.STATUS_AVAILABLE, false);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE074", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL074\"}", null))
        .when().put("/workers/" + id).then().statusCode(200);

        assertTrue(!fixtures.driverRowOf(id).isActive(),
            "la ficha de quien se fue no se enciende editandolo");
    }

    // ---------- el bloqueo de la fila ----------

    /**
     * Si otra operacion tiene tomada la fila, la edicion se rinde con el conflicto del contrato y
     * no con un error del servidor.
     *
     * <p>Es determinista: la otra conexion toma la fila y NO confirma, asi que la edicion espera
     * su tope y se rinde. Mide las dos mitades a la vez: que la fila se toma con bloqueo al
     * leerla, y que el choque se traduce en vez de escaparse.
     */
    @Test
    void update_whenAnotherTransactionHoldsTheRow_returns409_WRK013() throws Exception {
        int id = seedWorker("ZTESTE080", "operator");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                "SELECT id FROM public.workers WHERE id = ? FOR NO KEY UPDATE")) {
                lock.setInt(1, id);
                lock.executeQuery().close();
            }

            Future<Respuesta> response = executor.submit(() -> {
                var extracted = put(adminToken()).body(unchangedBody("ZTESTE080", "operator"))
                    .when().put("/workers/" + id).then().extract();
                // Tipado explicito: el extractor devuelve un generico y dejar que lo infiera el
                // destino lo resuelve mal en tiempo de ejecucion.
                String code = extracted.path("code");
                return new Respuesta(extracted.statusCode(), code);
            });

            var result = response.get(30, TimeUnit.SECONDS);
            assertEquals(409, result.statusCode(), "el choque es transitorio, no un error interno");
            assertEquals("WRK-013", result.code());
            holder.rollback();
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                // Se AVISA y no se afirma: este bloque corre al final, asi que una asercion aca
                // reemplazaria la falla verdadera del caso y se perderia el diagnostico.
                System.err.println("AVISO: quedo un hilo vivo del caso de bloqueo de la edicion");
            }
        }
    }

    /**
     * Y TAMPOCO el cargo de su propia CUENTA, que es la fila que da los permisos.
     *
     * <p>Es el cuerpo que la guarda vieja no veia: reenvia el cargo del TRABAJADOR sin cambios, asi
     * que mirando esa fila no se mueve nada, mientras la cascada mueve el de la cuenta. Con las dos
     * filas divergentes, un administrador se degradaba solo y recibia 200. La divergencia se siembra
     * a mano porque ninguna ruta de la aplicacion la produce.
     */
    @Test
    void update_admin_demotingItsOwnAccountThroughTheCascade_returns403_WRK012() {
        var actor = fixtures.seedActor("admin", "99");
        fixtures.setWorkerDocumentType(actor.workerId(), documentTypeId());
        fixtures.setWorkerHireDate(actor.workerId(), LocalDate.of(2024, 3, 1));
        // El trabajador baja de cargo; la CUENTA se queda en admin.
        fixtures.setWorkerRole(actor.workerId(), "general_manager");

        put(fabricateTokenForUser(actor.userId(), "ztestuser99", "admin"))
            .body(body("Actor", "admin", "ZTESTA99", null, "general_manager", "2024-03-01", null, null))
        .when().put("/workers/" + actor.workerId())
        .then().statusCode(403).body("code", equalTo("WRK-012"));

        assertEquals("admin", fixtures.userRoleNameOf(actor.userId()), "la cuenta no se movio");
        assertEquals(List.of(), auditFieldNames(actor.workerId()));
    }

    /**
     * El choque de bloqueo en la FASE DE ESCRITURA, no al tomar la fila: la otra conexion sostiene
     * la fila de la FICHA y el cuerpo cambia la licencia.
     *
     * <p>El caso de mas arriba sostiene la fila del trabajador, asi que el pedido muere al tomarla
     * y nunca llega a escribir: mide el traductor de la LECTURA. Este llega hasta el final, que es
     * donde el envoltorio del traductor sobre toda la fase de escritura es lo unico que separa el
     * conflicto del contrato de un error del servidor.
     */
    @Test
    void update_whenAnotherTransactionHoldsTheProfileRow_returns409_WRK013() throws Exception {
        int id = seedWorker("ZTESTE160", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL160", "A-IIb", WarehouseTestData.STATUS_AVAILABLE, true);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                "SELECT id FROM public.drivers WHERE worker_id = ? FOR UPDATE")) {
                lock.setInt(1, id);
                lock.executeQuery().close();
            }

            Future<Respuesta> response = executor.submit(() -> {
                var extracted = put(adminToken())
                    .body(body("Juan", "Pérez", "ZTESTE160", null, "driver", "2024-03-01",
                        "{\"licenseNumber\":\"ZTESTL999\",\"licenseCategory\":\"A-IIb\"}", null))
                    .when().put("/workers/" + id).then().extract();
                String code = extracted.path("code");
                return new Respuesta(extracted.statusCode(), code);
            });

            var result = response.get(30, TimeUnit.SECONDS);
            assertEquals(409, result.statusCode(), "el choque al ESCRIBIR tampoco es un error interno");
            assertEquals("WRK-013", result.code());
            holder.rollback();
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("AVISO: quedo un hilo vivo del caso de bloqueo de la ficha");
            }
        }
    }

    /**
     * El mismo choque sobre la fila de la CUENTA, que es la ultima espera del presupuesto y la
     * unica que se escribe sin haberla tomado con bloqueo.
     *
     * <p>Este trabajador NO tiene ficha, asi que el par con el de arriba cubre los dos caminos:
     * con ficha previa y sin ella. Sin el envoltorio, este sale 500.
     */
    @Test
    void update_whenAnotherTransactionHoldsTheAccountRow_returns409_WRK013() throws Exception {
        int id = seedWorker("ZTESTE161", "operator");
        fixtures.seedUserFor(id, "ztestuser161", "operator");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                "SELECT id FROM public.users WHERE worker_id = ? FOR UPDATE")) {
                lock.setInt(1, id);
                lock.executeQuery().close();
            }

            Future<Respuesta> response = executor.submit(() -> {
                var extracted = put(adminToken()).body(unchangedBody("ZTESTE161", "dispatcher"))
                    .when().put("/workers/" + id).then().extract();
                String code = extracted.path("code");
                return new Respuesta(extracted.statusCode(), code);
            });

            var result = response.get(30, TimeUnit.SECONDS);
            assertEquals(409, result.statusCode());
            assertEquals("WRK-013", result.code());
            holder.rollback();
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("AVISO: quedo un hilo vivo del caso de bloqueo de la cuenta");
            }
        }
    }

    /**
     * Y el choque en la rama que CREA la ficha, que es el UNICO camino que distingue el alcance del
     * envoltorio del traductor.
     *
     * <p>Los dos casos de arriba sostienen filas que ya existen, y esas escrituras salen en la
     * descarga FINAL: con el envoltorio angosto —cubriendo solo esa descarga— los dos siguen
     * verdes. La ficha que NACE se graba con una descarga propia, antes, y ahi el envoltorio
     * angosto deja escapar el choque como error del servidor. Sin este caso, achicar el envoltorio
     * a lo que era no rompe nada y el comentario que dice "TODA la fase de escritura" es una
     * afirmacion sin medir.
     *
     * <p>Es determinista sin depender de tiempos: la otra conexion inserta una ficha con la MISMA
     * licencia y no confirma, asi que el INSERT de esta edicion espera contra esa entrada del
     * indice unico hasta agotar su tope. El chequeo previo de licencia no la ve —esta sin
     * confirmar— asi que el pedido llega hasta la escritura, que es donde tiene que medirse.
     */
    @Test
    void update_creatingTheProfile_whenAnotherTransactionHoldsTheLicense_returns409_WRK013()
            throws Exception {
        int id = seedWorker("ZTESTE170", "operator");
        int otro = seedWorker("ZTESTE171", "driver");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement insert = holder.prepareStatement(
                "INSERT INTO public.drivers (worker_id, license_number, category, status_id, "
                    + "is_active) VALUES (?, 'ZTESTL170', 'A-IIb', ?, true)")) {
                insert.setInt(1, otro);
                insert.setInt(2, fixtures.resourceStatusId(WarehouseTestData.STATUS_AVAILABLE));
                insert.executeUpdate();
            }

            Future<Respuesta> response = executor.submit(() -> {
                var extracted = put(adminToken())
                    .body(body("Juan", "Pérez", "ZTESTE170", null, "driver", "2024-03-01",
                        "{\"licenseNumber\":\"ZTESTL170\",\"licenseCategory\":\"A-IIb\"}", null))
                    .when().put("/workers/" + id).then().extract();
                String code = extracted.path("code");
                return new Respuesta(extracted.statusCode(), code);
            });

            var result = response.get(30, TimeUnit.SECONDS);
            assertEquals(409, result.statusCode(),
                "el choque en la descarga de la ficha que nace tampoco es un error interno");
            assertEquals("WRK-013", result.code());
            holder.rollback();
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("AVISO: quedo un hilo vivo del caso de la ficha que nace");
            }
        }
    }

    // ---------- 404, permisos y orden ----------

    @Test
    void update_withAnIdThatDoesNotExist_returns404_WRK001() {
        put(adminToken()).body(unchangedBody("ZTESTE090", "operator"))
        .when().put("/workers/999999")
        .then().statusCode(404).body("code", equalTo("WRK-001"));
    }

    @ParameterizedTest(name = "id {0} -> 404")
    @ValueSource(ints = {0, -1})
    void update_withIdZeroOrNegative_returns404_WRK001(int id) {
        put(adminToken()).body(unchangedBody("ZTESTE091", "operator"))
        .when().put("/workers/" + id)
        .then().statusCode(404).body("code", equalTo("WRK-001"));
    }

    @Test
    void update_withANonNumericId_returns404WithoutBody() {
        String cuerpo = put(adminToken()).body(unchangedBody("ZTESTE092", "operator"))
            .when().put("/workers/abc").then().statusCode(404).extract().asString();

        // SIN cuerpo, que es lo que lo distingue del 404 del trabajador inexistente: los dos
        // comparten el numero, y sin esta asercion el nombre del caso promete mas de lo que mide.
        assertEquals("", cuerpo, "el conversor de la ruta falla antes de llegar al recurso");
    }

    @Test
    void update_withoutToken_returns401() {
        given().contentType("application/json").body(unchangedBody("ZTESTE093", "operator"))
        .when().put("/workers/1").then().statusCode(401);
    }

    @ParameterizedTest(name = "rol {0} no puede editar")
    @CsvSource({"warehouse_keeper, 60", "sales, 61", "dispatcher, 62"})
    void update_withARoleOutsideTheFour_returns403_COM003(String role, String suffix) {
        var actor = fixtures.seedActor(role, suffix);
        int id = seedWorker("ZTESTE09" + suffix.charAt(1), "operator");

        put(fabricateTokenForUser(actor.userId(), "ztestuser" + suffix, role))
            .body(unchangedBody("ZTESTE09" + suffix.charAt(1), "operator"))
        .when().put("/workers/" + id)
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    /** El cuerpo se valida ANTES de buscar el trabajador: es el unico caso que ancla ese escalon. */
    @Test
    void order_invalidBodyBeatsANonexistentId() {
        put(adminToken()).body("{}")
        .when().put("/workers/999999")
        .then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void order_notFoundBeatsAnInvalidRole() {
        put(adminToken()).body(unchangedBody("ZTESTE094", "no_existe"))
        .when().put("/workers/999999")
        .then().statusCode(404).body("code", equalTo("WRK-001"));
    }

    @Test
    void order_theDocumentTypeBeatsTheReason() {
        int id = seedWorker("ZTESTE095", "operator");

        put(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":999999,
             "documentNumber":"ZTESTE096","role":"operator","hireDate":"2024-03-01"}""")
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-003"));
    }

    @Test
    void order_theReasonBeatsADuplicateDocument() {
        int id = seedWorker("ZTESTE097", "operator");
        fixtures.seedWorker("ZTESTE098", "Ana", "Silva", "operator", true);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE098", null, "operator", "2024-03-01", null, null))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-009"));
    }

    // ---------- el cuerpo nuevo no perdio anotaciones ----------

    @Test
    void update_withEmptyBody_returns400_COM001() {
        put(adminToken()).body("")
        .when().put("/workers/1").then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void update_withExplicitNullBody_returns400_COM001() {
        put(adminToken()).body("null")
        .when().put("/workers/1").then().statusCode(400).body("code", equalTo("COM-001"));
    }

    @Test
    void update_withAnEmptyJsonObject_reportsTheSixRequiredFields() {
        put(adminToken()).body("{}")
        .when().put("/workers/1")
        .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", containsInAnyOrder(
                "firstName", "lastName", "documentTypeId", "documentNumber", "role", "hireDate"));
    }

    @Test
    void update_withAReasonOverItsMaximum_returns400_COM001() {
        int id = seedWorker("ZTESTE099", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE099", null, "operator", "2024-03-01",
                null, "A".repeat(501)))
        .when().put("/workers/" + id)
        .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("reason"));
    }

    @Test
    void update_withABlankLicenseNumber_returns400_COM001_namingTheNestedField() {
        int id = seedWorker("ZTESTE100", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL100", null, WarehouseTestData.STATUS_AVAILABLE, true);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE100", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"   \"}", null))
        .when().put("/workers/" + id)
        .then().statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("driver.licenseNumber"));
    }


    /** El status y el codigo de un pedido hecho en otro hilo. */
    private record Respuesta(int statusCode, String code) {}


    /**
     * El MISMO actor edita dos veces sin cambiar nada, y la marca se mueve igual.
     *
     * <p>Este caso existe porque su hermano, con un actor distinto, no puede fallar: al cambiar
     * la firma la entidad queda sucia sola y el callback mueve la marca aunque nadie se lo pida.
     * Con el mismo actor no cambia ninguna columna, asi que mover la marca es lo unico que puede
     * ensuciar la fila, y es exactamente la linea que la regla pide.
     */
    @Test
    void update_twiceByTheSameActorWithoutChanges_stillMovesUpdatedAt() {
        var actor = fixtures.seedActor("general_manager", "74");
        String token = fabricateTokenForUser(actor.userId(), "ztestuser74", "general_manager");
        int id = seedWorker("ZTESTE049", "operator");

        put(token).body(unchangedBody("ZTESTE049", "operator"))
            .when().put("/workers/" + id).then().statusCode(200);
        var afterFirst = fixtures.workerRowOf(id);

        put(token).body(unchangedBody("ZTESTE049", "operator"))
            .when().put("/workers/" + id).then().statusCode(200);

        assertNotEquals(afterFirst.updatedAt(), fixtures.workerRowOf(id).updatedAt(),
            "la marca se mueve aunque no cambie ninguna columna");
        assertEquals(List.of(), auditFieldNames(id), "y sigue sin dejar rastro de un cambio");
    }

    /** Apagar la ficha deja su propia fila de rastro: no es un cambio invisible. */
    @Test
    void update_whenTheNewRoleTurnsTheProfileOff_auditsIt() {
        int id = seedWorker("ZTESTE075", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL075", null, WarehouseTestData.STATUS_AVAILABLE, true);

        put(adminToken()).body(unchangedBody("ZTESTE075", "operator"))
        .when().put("/workers/" + id).then().statusCode(200);

        assertEquals(List.of("role", "driver.isActive"), auditFieldNames(id));
        var row = fixtures.workerAuditRows(id).get(1);
        assertEquals("Ficha de conductor", row.fieldLabel());
        assertEquals("true", row.oldValue());
        assertEquals("false", row.newValue());
    }


    // ---------- lo que el endpoint ESCRIBE, no solo lo que decide ----------

    /**
     * El cargo llega a la FILA.
     *
     * <p>Este caso existe por un agujero que encontró una revisión: borrar la asignación del
     * cargo dejaba la suite entera en verde. El rastro de auditoría se arma ANTES de asignar, la
     * cascada a la cuenta usa la variable nueva, y las transiciones de ficha deciden con ella,
     * así que ninguna de las tres notaba que la columna se quedaba con el valor viejo. Era la
     * asignación central de la operación sin un solo oráculo.
     */
    @Test
    void update_changingTheRole_writesItToTheRow() {
        int id = seedWorker("ZTESTE110", "operator");

        put(adminToken()).body(unchangedBody("ZTESTE110", "dispatcher"))
        .when().put("/workers/" + id)
        .then().statusCode(200).body("role.name", equalTo("dispatcher"));

        assertEquals(fixtures.roleIdOf("dispatcher"), fixtures.workerRowOf(id).roleId(),
            "el cargo decidido tiene que llegar a la columna");
    }

    /** Lo mismo para el tipo de documento, que tenía el mismo agujero. */
    @Test
    void update_changingTheDocumentType_writesItToTheRow() {
        int id = seedWorker("ZTESTE111", "operator");
        int otherType = fixtures.seedDocumentType("ZTDOC92", "ZTEST otro", 20, null, true);

        put(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,"documentNumber":"ZTESTE111",
             "role":"operator","hireDate":"2024-03-01"}""".formatted(otherType))
        .when().put("/workers/" + id)
        .then().statusCode(200).body("documentType.code", equalTo("ZTDOC92"));

        assertEquals(otherType, fixtures.workerRowOf(id).documentTypeId(),
            "contra la FILA, igual que su gemelo del cargo");
    }

    /** Y para los tres campos de texto, que llegan recortados. */
    @Test
    void update_trimsTheTextsBeforeWritingThem() {
        int id = seedWorker("ZTESTE112", "operator");

        put(adminToken()).body("""
            {"firstName":"  Ana  ","lastName":" Silva ","documentTypeId":%d,
             "documentNumber":"  ZTESTE112  ","role":"operator","hireDate":"2024-03-01"}"""
            .formatted(documentTypeId()))
        .when().put("/workers/" + id).then().statusCode(200);

        var row = fixtures.workerRowOf(id);
        assertEquals("Ana", row.firstName());
        assertEquals("Silva", row.lastName());
        assertEquals("ZTESTE112", row.documentNumber(),
            "sin recortar, el documento se leería como distinto del guardado y pediría motivo");
        assertEquals(List.of("firstName", "lastName"), auditFieldNames(id),
            "el documento recortado es el mismo: no es un cambio");
    }

    // ---------- los códigos del contrato que faltaban ----------

    @Test
    void update_withANumberLongerThanTheTypeMaxLength_returns400_WRK004() {
        int shortType = fixtures.seedDocumentType("ZTDOC93", "ZTEST corto", 9, null, true);
        int id = seedWorker("ZTESTE113", "operator");

        put(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTESTE1130","role":"operator","hireDate":"2024-03-01",
             "reason":"Correccion del documento pedida por la gerencia"}""".formatted(shortType))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-004"));
    }

    /**
     * El MISMO numero, con un tipo nuevo cuyo formato no lo admite: tambien es WRK-004.
     *
     * <p>Los dos casos de arriba cambian el tipo Y el numero a la vez, asi que no separan "el
     * numero no le calza a su tipo" de "el numero cambio". Validar el formato solo cuando el
     * numero cambia dejaba pasar este cuerpo con 200: un DNI de ocho digitos guardado bajo un tipo
     * de carnet que no los admite, y la fila afirmando un documento que su propio tipo rechaza.
     */
    @Test
    void update_keepingTheNumber_underANewTypeWhosePatternRejectsIt_returns400_WRK004() {
        int carnet = fixtures.seedDocumentType("ZTDOC70", "ZTEST carnet", 20, "^CE[0-9]{7}$", true);
        int id = seedWorker("ZTESTE180", "operator");

        put(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTESTE180","role":"operator","hireDate":"2024-03-01",
             "reason":"Correccion del tipo de documento pedida por la gerencia"}""".formatted(carnet))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-004"));

        assertEquals(documentTypeId(), fixtures.workerRowOf(id).documentTypeId(), "la fila no se movio");
        assertEquals(List.of(), auditFieldNames(id));
    }

    /** Y su gemelo por el largo: el mismo numero, mas largo que el maximo del tipo nuevo. */
    @Test
    void update_keepingTheNumber_underANewTypeTooShortForIt_returns400_WRK004() {
        int shortType = fixtures.seedDocumentType("ZTDOC71", "ZTEST corto", 8, null, true);
        int id = seedWorker("ZTESTE181", "operator");

        put(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTESTE181","role":"operator","hireDate":"2024-03-01",
             "reason":"Correccion del tipo de documento pedida por la gerencia"}""".formatted(shortType))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-004"));

        assertEquals(documentTypeId(), fixtures.workerRowOf(id).documentTypeId(), "la fila no se movio");
        assertEquals(List.of(), auditFieldNames(id));
    }

    @Test
    void update_withANumberThatDoesNotMatchThePattern_returns400_WRK004() {
        int patterned = fixtures.seedDocumentType("ZTDOC94", "ZTEST patron", 20, "^ZTEST[A-Z0-9]{4}$", true);
        int id = seedWorker("ZTESTE114", "operator");

        put(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTEST-115","role":"operator","hireDate":"2024-03-01",
             "reason":"Correccion del documento pedida por la gerencia"}""".formatted(patterned))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-004"));
    }

    /** Un cargo RETIRADO no se asigna, aunque exista. */
    @Test
    void update_withAnInactiveRole_returns400_WRK005() {
        fixtures.seedRole("ztestrole95", "ZTEST cargo retirado", 1, false, "NONE", false);
        int id = seedWorker("ZTESTE116", "operator");

        put(adminToken()).body(unchangedBody("ZTESTE116", "ztestrole95"))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-005"));
    }

    /** Y un tipo de documento retirado tampoco. */
    @Test
    void update_withAnInactiveDocumentType_returns400_WRK003() {
        int retired = fixtures.seedDocumentType("ZTDOC96", "ZTEST retirado", 20, null, false);
        int id = seedWorker("ZTESTE117", "operator");

        put(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,"documentNumber":"ZTESTE117",
             "role":"operator","hireDate":"2024-03-01"}""".formatted(retired))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-003"));
    }

    // ---------- la auditoría de la ficha ----------

    /** Crear la ficha durante una edición deja su rastro, igual que reactivarla. */
    @Test
    void update_whenTheNewRoleCreatesTheProfile_auditsIt() {
        int id = seedWorker("ZTESTE118", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE118", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL118\",\"licenseCategory\":\"A-IIIc\"}", null))
        .when().put("/workers/" + id).then().statusCode(200);

        assertEquals(List.of("role", "driver.isActive", "driver.licenseNumber",
            "driver.licenseCategory", "driver.status"), auditFieldNames(id));
        var rows = fixtures.workerAuditRows(id);
        assertNull(rows.get(1).oldValue(), "no habia ficha antes");
        assertEquals("true", rows.get(1).newValue());
        assertEquals("ZTESTL118", rows.get(2).newValue());
        // La disponibilidad con que nace es un dato de la fila desde el primer momento.
        assertNull(rows.get(4).oldValue());
        assertEquals("AVAILABLE", rows.get(4).newValue());
    }

    /** Cambiar la licencia y la categoría deja una fila cada una, con sus dos valores. */
    @Test
    void update_changingTheProfileFields_auditsEachOne() {
        int id = seedWorker("ZTESTE119", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL119", "A-IIIc", WarehouseTestData.STATUS_AVAILABLE, true);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE119", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL120\",\"licenseCategory\":\"A-IIb\"}", null))
        .when().put("/workers/" + id).then().statusCode(200);

        var rows = fixtures.workerAuditRows(id);
        assertEquals(List.of("driver.licenseNumber", "driver.licenseCategory"), auditFieldNames(id));
        assertEquals("ZTESTL119", rows.get(0).oldValue());
        assertEquals("ZTESTL120", rows.get(0).newValue());
        assertEquals("A-IIIc", rows.get(1).oldValue());
        assertEquals("A-IIb", rows.get(1).newValue());
    }

    /** El estado guardado se afirma como VALOR, no solo como nombre de campo. */
    @Test
    void update_changingTheProfileStatus_auditsBothValues() {
        int id = seedWorker("ZTESTE121", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL121", null, WarehouseTestData.STATUS_MAINTENANCE, true);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE121", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL121\",\"status\":\"NOT_AVAILABLE\"}", null))
        .when().put("/workers/" + id).then().statusCode(200);

        var row = fixtures.workerAuditRows(id).get(0);
        assertEquals("MAINTENANCE", row.oldValue(), "el estado que estaba guardado");
        assertEquals("NOT_AVAILABLE", row.newValue());
        assertEquals(fixtures.lowestResourceStatusIdIgnoringCase("not_available"),
            fixtures.driverRowOf(id).statusId(), "y la fila apunta a esa fila del catalogo");
    }

    /** Reenviar el mismo estado no es un cambio. */
    @Test
    void update_resendingTheSameProfileStatus_writesNoRow() {
        int id = seedWorker("ZTESTE122", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL122", null, WarehouseTestData.STATUS_MAINTENANCE, true);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE122", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL122\",\"status\":\"MAINTENANCE\"}", null))
        .when().put("/workers/" + id).then().statusCode(200);

        assertEquals(List.of(), auditFieldNames(id));
    }

    // ---------- el bloqueante: la ficha de un dado de baja se corrige ----------

    /**
     * A un trabajador DADO DE BAJA se le puede corregir la licencia, y su ficha sigue apagada.
     *
     * <p>Antes no había ningún cuerpo que lo permitiera: mandar la ficha se descartaba en
     * silencio con 200, y no mandarla era un rechazo porque el cargo la exige. Son dos ejes y
     * este caso mide los dos a la vez.
     */
    @Test
    void update_theProfileOfAnInactiveWorker_updatesItsDataAndStaysOff() {
        int id = fixtures.seedWorker("ZTESTE123", "Juan", "Pérez", "driver", false);
        fixtures.setWorkerDocumentType(id, documentTypeId());
        fixtures.setWorkerHireDate(id, LocalDate.of(2024, 3, 1));
        fixtures.seedDriverProfileFor(id, "ZTESTL123", null, WarehouseTestData.STATUS_AVAILABLE, false);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE123", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL124\"}", null))
        .when().put("/workers/" + id).then().statusCode(200);

        var row = fixtures.driverRowOf(id);
        assertEquals("ZTESTL124", row.licenseNumber(), "la correccion se guarda");
        assertTrue(!row.isActive(), "y la ficha de quien se fue sigue apagada");
        assertEquals(List.of("driver.licenseNumber"), auditFieldNames(id));
    }

    /** Si venía encendida, se apaga: los datos se escriben y el estado lo manda la vigencia. */
    @Test
    void update_theActiveProfileOfAnInactiveWorker_isTurnedOffWhileWritingTheData() {
        int id = fixtures.seedWorker("ZTESTE125", "Juan", "Pérez", "driver", false);
        fixtures.setWorkerDocumentType(id, documentTypeId());
        fixtures.setWorkerHireDate(id, LocalDate.of(2024, 3, 1));
        fixtures.seedDriverProfileFor(id, "ZTESTL125", null, WarehouseTestData.STATUS_AVAILABLE, true);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE125", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL126\"}", null))
        .when().put("/workers/" + id).then().statusCode(200);

        var row = fixtures.driverRowOf(id);
        assertEquals("ZTESTL126", row.licenseNumber());
        assertTrue(!row.isActive());
        assertEquals(List.of("driver.licenseNumber", "driver.isActive"), auditFieldNames(id));
    }


    /**
     * La ficha que NACE APAGADA sobre un dado de baja tambien compara su licencia contra las demas.
     *
     * <p>Este mismo montaje afirmaba antes lo contrario: la transicion no creaba nada, la licencia
     * se descartaba en silencio y un duplicado respondia 200. Ahora el valor se escribe, asi que el
     * duplicado es un 409 y no queda una ficha a medias.
     *
     * <p>Lo que este caso NO distingue, medido: si la ficha que nace apagada queda afuera del
     * chequeo previo de licencia, la respuesta es la misma, porque el INSERT choca contra el indice
     * unico y el traductor devuelve el mismo codigo. Mide el RESULTADO, que es lo que ve quien
     * llama. Que el chequeo previo corra lo mide la funcion pura, que es donde se decide.
     */
    @Test
    void update_creatingTheProfileTurnedOff_withADuplicateLicense_returns409_WRK007() {
        int other = seedWorker("ZTESTE130", "driver");
        fixtures.seedDriverProfileFor(other, "ZTESTL130", null, WarehouseTestData.STATUS_AVAILABLE, true);

        int id = fixtures.seedWorker("ZTESTE131", "Juan", "Pérez", "driver", false);
        fixtures.setWorkerDocumentType(id, documentTypeId());
        fixtures.setWorkerHireDate(id, LocalDate.of(2024, 3, 1));

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE131", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL130\"}", null))
        .when().put("/workers/" + id).then().statusCode(409).body("code", equalTo("WRK-007"));

        assertNull(fixtures.driverRowOf(id), "el rechazo no deja una ficha a medias");
    }

    /**
     * Un dado de baja SIN ficha, con un cargo que la lleva y la ficha en el cuerpo: la fila NACE
     * APAGADA con los datos del cuerpo.
     *
     * <p>Antes la licencia se descartaba en silencio con 200, sin rastro ni chequeo de unicidad; y
     * con un cargo que la EXIGE, la API pedia un campo que despues ignoraba, dejando un conductor
     * sin ficha que la reactivacion devolveria asi. Se miran las dos cosas: la respuesta, que trae
     * la ficha apagada, y la FILA, que es donde un 200 podria mentir.
     */
    @Test
    void update_anInactiveWorkerWithoutProfile_createsItTurnedOff() {
        int id = fixtures.seedWorker("ZTESTE172", "Juan", "Pérez", "driver", false);
        fixtures.setWorkerDocumentType(id, documentTypeId());
        fixtures.setWorkerHireDate(id, LocalDate.of(2024, 3, 1));

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE172", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL172\",\"licenseCategory\":\"A-IIb\"}", null))
        .when().put("/workers/" + id).then().statusCode(200)
            .body("driver.licenseNumber", equalTo("ZTESTL172"))
            .body("driver.isActive", equalTo(false));

        var ficha = fixtures.driverRowOf(id);
        assertNotNull(ficha, "la ficha del cuerpo se guarda, no se descarta");
        assertEquals("ZTESTL172", ficha.licenseNumber());
        assertEquals("A-IIb", ficha.category());
        assertEquals(fixtures.lowestResourceStatusIdIgnoringCase("available"), ficha.statusId(),
            "con la disponibilidad de omision, resuelta como la resuelve el servicio");
        assertTrue(!ficha.isActive(), "y APAGADA: nacer encendida lo devolveria al catalogo");
        assertTrue(!fixtures.workerRowOf(id).isActive(), "el trabajador sigue de baja");

        // El rastro dice lo que la fila tiene: licencia, categoria y estado, y NO un encendido.
        assertEquals(List.of("driver.licenseNumber", "driver.licenseCategory", "driver.status"),
            auditFieldNames(id));
        var rows = fixtures.workerAuditRows(id);
        assertEquals("ZTESTL172", rows.get(0).newValue());
        assertEquals("A-IIb", rows.get(1).newValue());
        assertNull(rows.get(2).oldValue(), "no habia ficha antes");
        assertEquals("AVAILABLE", rows.get(2).newValue());
    }

    /**
     * La jerarquia mira tambien el cargo de la CUENTA, que es la fila que esta operacion escribe.
     *
     * <p>Hoy el cargo del trabajador y el de su cuenta siempre coinciden, asi que la guarda es un
     * no-op y ningun caso normal la distingue. Este los separa a proposito, sembrando la
     * divergencia por fuera: sin la guarda, un gerente que ve un trabajador de cargo bajo le
     * degrada la cuenta a alguien que nunca estuvo debajo suyo en el organigrama, con 200.
     */
    @Test
    void update_theRankAlsoLooksAtTheAccountRole_notOnlyAtTheWorkerOne() {
        var actor = fixtures.seedActor("general_manager", "97");
        int id = seedWorker("ZTESTE132", "operator");
        int userId = fixtures.seedUserFor(id, "ztestuser98b", "operator");
        // La divergencia que hoy nada impide: la cuenta con un cargo por encima del actor.
        fixtures.setUserRole(userId, "admin");

        put(fabricateTokenForUser(actor.userId(), "ztestuser97", "general_manager"))
            .body(unchangedBody("ZTESTE132", "assistant"))
        .when().put("/workers/" + id)
        .then().statusCode(403).body("code", equalTo("WRK-006"));

        assertEquals("admin", fixtures.userRoleNameOf(userId), "la cuenta no se movio");
    }


    // ---------- los pares del orden que quedaban sin ancla ----------

    /** La correspondencia de la ficha se decide ANTES que el motivo. */
    @Test
    void order_theProfileMismatchBeatsTheReason() {
        int id = seedWorker("ZTESTE140", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL140", null, WarehouseTestData.STATUS_AVAILABLE, true);

        // Dos problemas a la vez: el cargo exige ficha y no viene, Y el documento cambia sin motivo.
        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE141", null, "driver", "2024-03-01", null, null))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-008"));
    }

    /** Y el documento duplicado se decide antes que la licencia duplicada. */
    @Test
    void order_theDuplicateDocumentBeatsTheDuplicateLicense() {
        int otro = seedWorker("ZTESTE142", "driver");
        fixtures.seedDriverProfileFor(otro, "ZTESTL142", null, WarehouseTestData.STATUS_AVAILABLE, true);
        int id = seedWorker("ZTESTE143", "driver");
        fixtures.seedDriverProfileFor(id, "ZTESTL143", null, WarehouseTestData.STATUS_AVAILABLE, true);

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE142", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL142\"}", "Correccion pedida por la gerencia"))
        .when().put("/workers/" + id)
        .then().statusCode(409).body("code", equalTo("WRK-002"));
    }

    /**
     * El par que faltaba de la cadena publicada: el numero que no le entra a su tipo GANA sobre la
     * correspondencia de la ficha.
     *
     * <p>Los dos casos de WRK-004 usan un cargo sin ficha, asi que nunca conviven con el rechazo de
     * correspondencia y el par quedaba sin ancla mientras el javadoc de la cadena afirmaba que cada
     * par contiguo tenia la suya. Sin este caso, intercambiar los dos bloques cambia el error que
     * ve quien manda un documento largo con un cargo que exige ficha, y nada lo nota.
     */
    @Test
    void order_theDocumentThatDoesNotFitItsTypeBeatsTheProfileMismatch() {
        int shortType = fixtures.seedDocumentType("ZTDOC93", "ZTEST corto", 9, null, true);
        int id = seedWorker("ZTESTE162", "operator");

        // Documento de 10 sobre un tipo de 9 Y un cargo que exige ficha, sin ficha en el cuerpo.
        put(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":%d,
             "documentNumber":"ZTESTE1620","role":"driver","hireDate":"2024-03-01",
             "reason":"Correccion del documento pedida por la gerencia"}""".formatted(shortType))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-004"));
    }

    /** El rechazo por cuenta sin sesion se decide antes que el tipo de documento. */
    @Test
    void order_theAccountWithoutLoginBeatsAnInvalidDocumentType() {
        int id = seedWorker("ZTESTE144", "sales");
        fixtures.seedUserFor(id, "ztestuser144", "sales");

        put(adminToken()).body("""
            {"firstName":"Juan","lastName":"Pérez","documentTypeId":999999,
             "documentNumber":"ZTESTE144","role":"driver","hireDate":"2024-03-01"}""")
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-011"));
    }

    /** Crear la ficha con un estado explicito lo guarda, y lo deja dicho en el rastro. */
    @Test
    void update_whenTheProfileIsCreatedWithAStatus_itIsStored() {
        int id = seedWorker("ZTESTE145", "operator");

        put(adminToken()).body(body("Juan", "Pérez", "ZTESTE145", null, "driver", "2024-03-01",
                "{\"licenseNumber\":\"ZTESTL145\",\"status\":\"MAINTENANCE\"}", null))
        .when().put("/workers/" + id)
        .then().statusCode(200).body("driver.status", equalTo("MAINTENANCE"));

        assertEquals(fixtures.lowestResourceStatusIdIgnoringCase("maintenance"),
            fixtures.driverRowOf(id).statusId(), "el estado pedido llega a la fila");

        // Y el rastro dice el PEDIDO, no el de omision: los otros casos que miran el rastro de una
        // ficha que nace la mandan sin estado, asi que sin esto el historial podia decir
        // AVAILABLE sobre una ficha que nacio en MAINTENANCE y ningun caso se enteraba.
        var estado = fixtures.workerAuditRows(id).stream()
            .filter(r -> "driver.status".equals(r.fieldName())).toList();
        assertEquals(1, estado.size(), "la disponibilidad con que nace deja una fila");
        assertNull(estado.get(0).oldValue());
        assertEquals("MAINTENANCE", estado.get(0).newValue());
    }


    /**
     * La cascada a la cuenta se decide por el cargo DE LA CUENTA, no por el del trabajador.
     *
     * <p>Hoy los dos coinciden siempre y ningun caso normal los separa. Este los separa a
     * proposito, sembrando la divergencia por fuera, y usa el unico cuerpo que los distingue: uno
     * que reenvia el cargo del TRABAJADOR sin cambios. Comparando el del trabajador, el metodo
     * sale temprano y la cuenta se queda con un cargo que ya no le corresponde, sin rastro.
     */
    @Test
    void update_theAccountCascade_isDecidedByTheAccountRole_notTheWorkerOne() {
        int id = seedWorker("ZTESTE150", "dispatcher");
        int userId = fixtures.seedUserFor(id, "ztestuser150", "dispatcher");
        fixtures.setUserRole(userId, "sales");

        // El cuerpo reenvia el cargo del trabajador SIN cambios.
        put(adminToken()).body(unchangedBody("ZTESTE150", "dispatcher"))
        .when().put("/workers/" + id).then().statusCode(200);

        assertEquals("dispatcher", fixtures.userRoleNameOf(userId),
            "la cuenta tiene que alinearse con el cargo del trabajador");
        assertEquals(List.of("user.role"), auditFieldNames(id));

        // Y los VALORES del rastro, no solo que exista una fila con ese nombre: sin esto, invertir
        // los dos argumentos de la comparacion deja el caso verde con el historial al reves.
        var rastro = fixtures.workerAuditRows(id).get(0);
        assertEquals("sales", rastro.oldValue(), "de donde venia la cuenta");
        assertEquals("dispatcher", rastro.newValue(), "adonde fue");
        assertEquals(fixtures.roleIdOf("dispatcher"), fixtures.workerRowOf(id).roleId(),
            "el cargo del trabajador no se movio: el cuerpo lo reenvio igual");
    }

    /**
     * Y el rechazo por cuenta sin sesion, tambien.
     *
     * <p>Mismo montaje: el cuerpo reenvia el cargo del trabajador, que no inicia sesion, y la
     * cuenta esta en otro que si. Mirando el del trabajador, el metodo sale temprano y la cuenta
     * termina con un cargo sin permisos, que es exactamente lo que esta regla existe para impedir.
     */
    @Test
    void update_theLoginCheck_isDecidedByTheAccountRole_notTheWorkerOne() {
        int id = seedWorker("ZTESTE151", "operator");
        int userId = fixtures.seedUserFor(id, "ztestuser151", "operator");
        fixtures.setUserRole(userId, "sales");

        put(adminToken()).body(unchangedBody("ZTESTE151", "operator"))
        .when().put("/workers/" + id)
        .then().statusCode(400).body("code", equalTo("WRK-011"));

        assertEquals("sales", fixtures.userRoleNameOf(userId), "la cuenta no se movio");
    }

}
