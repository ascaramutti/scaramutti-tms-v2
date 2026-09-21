package com.scaramutti.tms.workers;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static com.scaramutti.tms.support.TestAuth.adminToken;
import static com.scaramutti.tms.support.TestAuth.fabricateAccessToken;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests de GET /workers/{id}.
 *
 * <p>Limpia ANTES y DESPUES de cada caso: una corrida cortada a la mitad deja un documento
 * de prueba que rompe la siguiente por unicidad, y el error culpa al fixture.
 *
 * <p>Los cuatro roles que leen el detalle entran por token fabricado y no por sesion real:
 * ninguna de las lecturas de esta unidad resuelve el usuario del sujeto del token, asi que
 * fabricar el rol mide exactamente lo que la guarda decide.
 */
@QuarkusTest
class WorkerDetailResourceTest {

    @Inject WarehouseTestData fixtures;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
        fixtures.deleteTestRoles();
        // Despues de los trabajadores: el trabajador apunta a su tipo de documento.
        fixtures.deleteTestDocumentTypes();
    }

    // ---------- la ficha completa ------------------------------------------------

    @Test
    void get_returnsTheFifteenFieldsOfTheContract() {
        int id = fixtures.seedWorker("ZTESTB001", "Juan", "Pérez Huamán", "driver", true);
        fixtures.setWorkerPhone(id, "987654321");
        fixtures.setWorkerHireDate(id, LocalDate.of(2024, 3, 1));
        fixtures.seedDriverProfileFor(id, "ZTESTL001", "A-IIIc", WarehouseTestData.STATUS_AVAILABLE, true);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200)
            .contentType("application/json")
            // El conjunto ENTERO de claves y no campo por campo: asi un campo de mas, que es
            // como se filtra un dato sin querer, tambien hace fallar el caso.
            .body("keySet()", containsInAnyOrder(
                "id", "firstName", "lastName", "documentType", "documentNumber", "phone",
                "role", "hireDate", "isActive", "createdAt", "createdBy", "updatedAt",
                "updatedBy", "driver", "hasUser"))
            .body("id", equalTo(id))
            .body("firstName", equalTo("Juan"))
            .body("lastName", equalTo("Pérez Huamán"))
            .body("documentNumber", equalTo("ZTESTB001"))
            .body("phone", equalTo("987654321"))
            .body("isActive", equalTo(true))
            .body("createdAt", notNullValue())
            .body("updatedAt", notNullValue());
    }

    /** El rol viaja entero: el formulario necesita el nivel y la modalidad del cargo actual. */
    @Test
    void get_carriesTheRoleWithItsLevelLoginAndDriverProfile() {
        int id = fixtures.seedWorker("ZTESTB002", "Ana", "Silva", "driver", true);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200)
            .body("role.name", equalTo("driver"))
            .body("role.description", equalTo("Conductor"))
            .body("role.level", equalTo(1))
            .body("role.canLogin", equalTo(false))
            .body("role.driverProfile", equalTo("REQUIRED"));
    }

    /** El tipo de documento viaja con lo que la escritura necesita para validar el numero. */
    @Test
    void get_carriesTheDocumentTypeWithItsMaxLength() {
        int id = fixtures.seedWorker("ZTESTB003", "Luis", "Vega", "operator", true);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200)
            .body("documentType.id", notNullValue())
            .body("documentType.code", equalTo("DNI"))
            .body("documentType.maxLength", notNullValue());
    }

    /** Dia calendario, sin hora ni zona: no es un instante. */
    @Test
    void get_hireDateTravelsAsACalendarDay() {
        int id = fixtures.seedWorker("ZTESTB004", "Rosa", "Diaz", "operator", true);
        fixtures.setWorkerHireDate(id, LocalDate.of(2024, 3, 1));

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200).body("hireDate", equalTo("2024-03-01"));
    }

    @Test
    void get_workerWithoutPhone_travelsNull() {
        int id = fixtures.seedWorker("ZTESTB005", "Pedro", "Rios", "operator", true);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200).body("phone", nullValue());
    }

    /**
     * Un trabajador inactivo se abre igual. Es justamente como se lo vuelve a activar: si el
     * detalle filtrara por activo, reactivar a alguien exigiria SQL, que es lo que este
     * modulo viene a eliminar.
     */
    @Test
    void get_inactiveWorker_isReturnedAnyway() {
        int id = fixtures.seedWorker("ZTESTB006", "Ines", "Torres", "operator", false);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200).body("isActive", equalTo(false));
    }

    // ---------- la ficha de conductor --------------------------------------------

    @Test
    void get_workerWithoutDriverProfile_travelsDriverNull() {
        int id = fixtures.seedWorker("ZTESTB007", "Carlos", "Ramirez", "operator", true);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200).body("driver", nullValue()).body("hasUser", equalTo(false));
    }

    @Test
    void get_driverProfile_carriesLicenseCategoryAndState() {
        int id = fixtures.seedWorker("ZTESTB008", "Mario", "Lopez", "driver", true);
        int driverId = fixtures.seedDriverProfileFor(
            id, "ZTESTL008", "A-IIIc", WarehouseTestData.STATUS_AVAILABLE, true);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200)
            .body("driver.id", equalTo(driverId))
            .body("driver.licenseNumber", equalTo("ZTESTL008"))
            .body("driver.licenseCategory", equalTo("A-IIIc"))
            .body("driver.isActive", equalTo(true));
    }

    /**
     * Una ficha apagada se sigue viendo. Es justo la que el formulario de edicion tiene que
     * conocer para no crear otra al lado cuando el cargo vuelva a llevarla.
     */
    @Test
    void get_workerWithInactiveDriverProfile_stillShowsIt() {
        int id = fixtures.seedWorker("ZTESTB009", "Elena", "Paz", "operator", true);
        fixtures.seedDriverProfileFor(id, "ZTESTL009", null, WarehouseTestData.STATUS_AVAILABLE, false);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200)
            .body("driver.isActive", equalTo(false))
            .body("driver.licenseCategory", nullValue());
    }

    /** La disponibilidad sale con el dominio de la API y no con el nombre del catalogo. */
    @ParameterizedTest
    @CsvSource({
        WarehouseTestData.STATUS_AVAILABLE + ", AVAILABLE",
        WarehouseTestData.STATUS_MAINTENANCE + ", MAINTENANCE",
        WarehouseTestData.STATUS_NOT_AVAILABLE + ", NOT_AVAILABLE",
    })
    void get_driverStatus_isTranslatedToTheApiDomain(String catalogName, String apiStatus) {
        int id = fixtures.seedWorker("ZTESTB01" + apiStatus.charAt(0), "Sara", "Nuñez", "driver", true);
        fixtures.seedDriverProfileFor(id, "ZTESTL01" + apiStatus.charAt(0), null, catalogName, true);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200).body("driver.status", equalTo(apiStatus));
    }

    // ---------- si tiene usuario y quien lo toco ---------------------------------

    /**
     * Se pregunta por el trabajador y no por el id del usuario. Con un solo caso, un cruce
     * equivocado da el valor correcto por casualidad; por eso hay uno con y otro sin.
     */
    @Test
    void get_workerWithUser_hasUserIsTrue() {
        int id = fixtures.seedWorker("ZTESTB020", "Diana", "Rojas", "operator", true);
        fixtures.seedUserFor(id, "ztestuser20", "sales");

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200).body("hasUser", equalTo(true));
    }

    /**
     * Las filas anteriores a este modulo no saben quien las cargo, y eso viaja como nulo y
     * no como un usuario inventado.
     */
    @Test
    void get_workerLoadedBeforeThisUnit_travelsWithoutAuthors() {
        int id = fixtures.seedWorker("ZTESTB021", "Hugo", "Medina", "operator", true);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200)
            .body("createdBy", nullValue())
            .body("updatedBy", nullValue())
            .body("createdAt", notNullValue())
            .body("updatedAt", notNullValue());
    }

    /**
     * Quien creo y quien modifico son dos personas distintas y cada una se presenta con el
     * nombre visible de SU cargo, igual que en el token de sesion.
     */
    @Test
    void get_authors_areTwoDifferentUsersWithTheirRoleVisibleName() {
        int creatorWorker = fixtures.seedWorker("ZTESTB022", "Cree", "Ador", "sales", true);
        int editorWorker = fixtures.seedWorker("ZTESTB023", "Edi", "Tor", "admin", true);
        int creator = fixtures.seedUserFor(creatorWorker, "ztestuser22", "sales");
        int editor = fixtures.seedUserFor(editorWorker, "ztestuser23", "admin");
        int id = fixtures.seedWorker("ZTESTB024", "Ficha", "Tocada", "operator", true);
        fixtures.setWorkerAudit(id, creator, editor);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200)
            .body("createdBy.id", equalTo(creator))
            .body("createdBy.username", equalTo("ztestuser22"))
            .body("createdBy.fullName", equalTo("Cree Ador"))
            .body("createdBy.position", equalTo("Ejecutivo de Ventas"))
            .body("updatedBy.id", equalTo(editor))
            .body("updatedBy.username", equalTo("ztestuser23"))
            .body("updatedBy.fullName", equalTo("Edi Tor"))
            .body("updatedBy.position", equalTo("Administrador del Sistema"));
    }

    // ---------- no encontrado ----------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"999999999", "0", "-1"})
    void get_idThatDoesNotExist_returns404WithItsCode(String id) {
        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(404)
            .contentType("application/problem+json")
            .body("code", equalTo("WRK-001"));
    }

    /** El conversor del parametro falla antes del recurso: 404 y sin cuerpo. */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "9999999999999999999999"})
    void get_idThatIsNotAnInteger_returns404WithoutBody(String id) {
        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(404).body(equalTo(""));
    }

    // ---------- quien puede leerlo -----------------------------------------------

    @Test
    void get_withoutToken_returns401() {
        given().when().get("/workers/1").then().statusCode(401);
    }

    /**
     * El encargado de almacen esta primero a proposito: es el unico de los tres que SI entra
     * al listado. Un {@code @RolesAllowed} copiado del listado al detalle muere exactamente
     * en este caso, y en ningun otro.
     */
    @ParameterizedTest
    @ValueSource(strings = {"warehouse_keeper", "sales", "dispatcher"})
    void get_withARoleOutsideTheFour_returns403(String role) {
        int id = fixtures.seedWorker("ZTESTB030", "Sin", "Acceso", "operator", true);

        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-" + role, role))
        .when().get("/workers/" + id)
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "general_manager", "operations_manager", "finance_manager"})
    void get_withEachMaintenanceRole_returns200(String role) {
        int id = fixtures.seedWorker("ZTESTB031", "Con", "Acceso", "operator", true);

        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-" + role, role))
        .when().get("/workers/" + id)
        .then().statusCode(200).body("id", equalTo(id));
    }

    /**
     * La lectura es plana: no hay regla de organigrama. Sin este caso, agregarle el rango al
     * detalle pasaria por "mas seguro" y dejaria a media empresa sin poder abrir una ficha.
     */
    @Test
    void get_aLowerRole_readsAWorkerOfAHigherRole() {
        int id = fixtures.seedWorker("ZTESTB032", "Jefe", "Maximo", "admin", true);

        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-fm", "finance_manager"))
        .when().get("/workers/" + id)
        .then().statusCode(200)
            .body("role.name", equalTo("admin"))
            // Con un rol de nivel 4 que si inicia sesion y no lleva ficha, los tres campos
            // toman valores distintos de los del conductor del caso de arriba: una constante
            // ya no puede satisfacer a los dos.
            .body("role.level", equalTo(4))
            .body("role.canLogin", equalTo(true))
            .body("role.driverProfile", equalTo("NONE"));
    }
    /**
     * Un cargo retirado se sigue mostrando en la ficha de quien lo tiene. El catalogo es el
     * que filtra por vigente, porque ofrece lo que se puede elegir hoy; el detalle cuenta lo
     * que hay. Sin este caso, filtrar por vigente en la consulta del detalle deja sin ficha a
     * todo el que tenga un cargo que se retiro, y el sintoma es un 404 que nadie explica.
     */
    @Test
    void get_workerWithARetiredRole_stillShowsIt() {
        fixtures.seedRole("ztestrole7", "ZTEST cargo retirado", 1, false, "NONE", false);
        int id = fixtures.seedWorker("ZTESTB040", "Vieja", "Guardia", "ztestrole7", true);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200)
            .body("role.name", equalTo("ztestrole7"))
            .body("role.description", equalTo("ZTEST cargo retirado"));
    }

    /**
     * Tiene usuario aunque la cuenta este apagada. Desactivar un trabajador apaga su usuario
     * y NO lo borra, asi que la pantalla necesita saber que la cuenta sigue ahi: si esto
     * dijera que no tiene, el formulario ofreceria crearle una segunda y chocaria contra la
     * unicidad con un 500 que nadie explica.
     */
    @Test
    void get_workerWithInactiveUser_hasUserIsStillTrue() {
        int id = fixtures.seedWorker("ZTESTB025", "Cuenta", "Apagada", "operator", true);
        fixtures.seedUserFor(id, "ztestuser25", "sales", false);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200).body("hasUser", equalTo(true));
    }

    /**
     * El tipo de documento viaja ENTERO y con valores propios, no con los de otra columna.
     * Se siembra un tipo con largo y patron distintos de los del documento nacional para que
     * leer el alias equivocado no pueda dar por casualidad el valor correcto.
     */
    @Test
    void get_documentType_carriesTheWholeTypeIncludingItsPattern() {
        int typeId = fixtures.seedDocumentType("ZTDOC7", "ZTEST carnet", 9, "^Z\\d{7}$", true);
        int id = fixtures.seedWorker("ZTESTB041", "Tipo", "Propio", "operator", true);
        fixtures.setWorkerDocumentType(id, typeId);

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200)
            .body("documentType.id", equalTo(typeId))
            .body("documentType.code", equalTo("ZTDOC7"))
            .body("documentType.name", equalTo("ZTEST carnet"))
            .body("documentType.maxLength", equalTo(9))
            .body("documentType.validationPattern", equalTo("^Z\\d{7}$"));
    }

    /**
     * Las dos marcas de tiempo no se intercambian. Con las dos puestas al mismo instante, o
     * afirmadas solo como "no nulas", intercambiarlas en la consulta pasa sin que nada falle.
     */
    @Test
    void get_theTwoTimestamps_doNotSwap() {
        int id = fixtures.seedWorker("ZTESTB042", "Dos", "Marcas", "operator", true);
        fixtures.setWorkerTimestamps(id,
            java.time.OffsetDateTime.parse("2024-01-02T10:00:00Z"),
            java.time.OffsetDateTime.parse("2025-06-07T18:30:00Z"));

        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/" + id)
        .then().statusCode(200)
            .body("createdAt", org.hamcrest.Matchers.startsWith("2024-01-02"))
            .body("updatedAt", org.hamcrest.Matchers.startsWith("2025-06-07"));
    }

    /**
     * El rol denegado contesta antes que la busqueda, tambien con un id que no existe. Hoy es
     * estructural, porque la lista de roles corre antes del metodo; el caso existe para que el
     * dia que esa regla se mueva al servicio, nadie gane un oraculo de existencia sobre el
     * padron.
     */
    @Test
    void get_withAForbiddenRole_andANonexistentId_stillReturns403() {
        given().header("Authorization", "Bearer " + fabricateAccessToken("ztest-wk", "warehouse_keeper"))
        .when().get("/workers/999999999")
        .then().statusCode(403).body("code", equalTo("COM-003"));
    }

    /** El cuerpo del error es el que publica el contrato, no solo su codigo. */
    @Test
    void get_theNotFoundBody_isTheOneThePublishedContractShows() {
        given().header("Authorization", "Bearer " + adminToken())
        .when().get("/workers/999999999")
        .then().statusCode(404)
            .body("code", equalTo("WRK-001"))
            .body("status", equalTo(404))
            .body("detail", equalTo("Trabajador no encontrado"));
    }

}
