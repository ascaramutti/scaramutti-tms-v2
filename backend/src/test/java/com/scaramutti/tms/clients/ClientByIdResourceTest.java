package com.scaramutti.tms.clients;

import com.scaramutti.tms.clients.dto.ClientResponse;
import com.scaramutti.tms.clients.service.ClientService;
import com.scaramutti.tms.clients.service.cmd.UpdateClientCommand;
import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.repository.ClientRepository;
import com.scaramutti.tms.support.HermeticTestData;
import com.scaramutti.tms.support.TestAuth;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.hibernate.exception.ConstraintViolationException;

import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /clients/{id}} y {@code PUT /clients/{id}}.
 *
 * <p>Vive aparte de {@code ClientsResourceTest} (que cubre el listado y el alta) por dos
 * motivos: aquel archivo ya pasa las mil líneas, y su limpieza borra por prefijo de RUC, así
 * que compartir prefijo haría que una clase barriera las fixtures de la otra.
 *
 * <p><b>Regla de las fixtures:</b> TODO RUC que aparezca acá, de origen y de destino, empieza
 * con {@code 91}. Una edición que deje un RUC fuera del prefijo se escapa de la limpieza, y la
 * corrida siguiente rompe por el UNIQUE de la razón social sin que el error diga por qué.
 */
@QuarkusTest
class ClientByIdResourceTest {

    @Inject ClientRepository clientRepository;
    @Inject EntityManager entityManager;
    @Inject ClientService clientService;
    @Inject HermeticTestData fixtures;

    /**
     * Prefijos propios y DISJUNTOS de los de {@code ClientsResourceTest} ({@code 90} y
     * {@code ZTEST_}). El de nombre no puede ser {@code ZTEST_BYID_}: seria prefijo del de la
     * clase hermana, que busca por {@code q=ZTEST_} y afirma sobre conteos exactos.
     */
    private static final String RUC_PREFIX = "91";
    private static final String NAME_PREFIX = "ZBYID_";

    /**
     * Reparte RUCs únicos dentro de CADA test sin numerarlos a mano. No es único por clase: con
     * el ciclo de vida por método, cada caso (y cada invocación parametrizada) arranca el
     * contador en cero. Alcanza porque la limpieza corre entre casos y borra el prefijo entero.
     */
    private final AtomicInteger rucCounter = new AtomicInteger(0);

    /**
     * La limpieza corre ANTES y DESPUÉS de cada caso a propósito. El contador de RUC se reinicia
     * por método, así que todos los casos piden el mismo primer RUC: una corrida que muera entre
     * el caso y su limpieza deja una fila que rompe casi toda la clase con un error de unicidad
     * que no dice por qué.
     */
    @BeforeEach
    void cleanupLeftovers() {
        cleanupFixtures();
    }

    @AfterEach
    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            // Primero lo que REFERENCIA al cliente. La clave foránea de cotizaciones no borra en
            // cascada, así que una cotización que sobreviva a un corte (Ctrl-C, timeout, OOM)
            // haría fallar esta limpieza, y como lo que falla es la limpieza, la clase entera
            // queda inarrancable hasta que alguien entre a mano a la base compartida.
            entityManager.createNativeQuery(
                "DELETE FROM cotizaciones.quotations WHERE client_id IN "
                    + "(SELECT id FROM clients WHERE ruc LIKE ?1)")
                .setParameter(1, RUC_PREFIX + "%")
                .executeUpdate();
            clientRepository.delete("ruc like ?1", RUC_PREFIX + "%");
        });
    }

    private String nextRuc() {
        return RUC_PREFIX + String.format("%09d", rucCounter.incrementAndGet());
    }

    /** Inserta un cliente y devuelve su id, que es lo que arma la URL de estos endpoints. */
    private Integer seedClient(String name, String ruc, boolean isActive) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Client client = new Client();
            client.name = name;
            client.ruc = ruc;
            client.isActive = isActive;
            clientRepository.persist(client);
            return client.id;
        });
    }

    private Integer seedClient(String name, String ruc) {
        return seedClient(name, ruc, true);
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

    // ---------- GET: happy path ----------------------------------------------

    @Test
    void get_existingActiveClient_returns200WithContractShape() {
        String ruc = nextRuc();
        Integer id = seedClient(NAME_PREFIX + "ACTIVO", ruc);

        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
        .when()
            .get("/clients/" + id)
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("id", equalTo(id))
            .body("name", equalTo(NAME_PREFIX + "ACTIVO"))
            .body("ruc", equalTo(ruc))
            .body("isActive", equalTo(true))
            .body("createdAt", notNullValue())
            .body("phone", nullValue())
            .body("contactName", nullValue());
    }

    /** El servicio NO filtra por isActive: un cliente desactivado también se lee. */
    @Test
    void get_inactiveClient_returns200() {
        Integer id = seedClient(NAME_PREFIX + "INACTIVO", nextRuc(), false);

        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
        .when()
            .get("/clients/" + id)
        .then()
            .statusCode(200)
            .body("isActive", equalTo(false));
    }

    // ---------- GET: 404 ------------------------------------------------------

    /** Un id numérico que no existe llega al servicio y sale como CLI-003 con cuerpo. */
    @ParameterizedTest
    @ValueSource(strings = {"999999999", "0", "-1"})
    void get_nonexistentOrZeroOrNegativeId_returns404_CLI003(String id) {
        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
        .when()
            .get("/clients/" + id)
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("code", equalTo("CLI-003"));
    }

    /**
     * Un id que NO ENCAJA en un entero de 32 bits no llega al recurso: el conversor de
     * parámetros falla antes del match de ruta y RESTEasy responde 404 SIN cuerpo. Se afirma el
     * cuerpo vacío y no solo el status, para que agregar un mapper del 404 de JAX-RS no pase
     * inadvertido.
     *
     * <p>Las dos formas de no encajar van juntas a propósito: el contrato nombra las dos y sus
     * caminos son distintos (una no es numérica, la otra se pasa de rango). Es el mismo par que
     * ya usan los tests de operaciones.
     */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "9999999999999999999999"})
    void get_idThatDoesNotFitAnInt_returns404WithoutBody(String id) {
        String cuerpo = given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
        .when()
            .get("/clients/" + id)
        .then()
            .statusCode(404)
            .extract().body().asString();

        assertTrue(cuerpo == null || cuerpo.isBlank(), "El 404 del id " + id + " trajo cuerpo: " + cuerpo);
    }

    // ---------- GET: sesión y roles ------------------------------------------

    @Test
    void get_withoutToken_returns401() {
        Integer id = seedClient(NAME_PREFIX + "SINTOKEN", nextRuc());

        given()
        .when()
            .get("/clients/" + id)
        .then()
            .statusCode(401);
    }

    /**
     * Sin `@RolesAllowed`: cualquier sesión lee. Los dos roles son de fuera de la lista del PUT,
     * así que un `@RolesAllowed` copiado del PUT, o cualquier lista angosta, muere acá.
     */
    @Test
    void get_withSalesRole_returns200() {
        Integer id = seedClient(NAME_PREFIX + "SALES", nextRuc());

        given()
            .header("Authorization", "Bearer " + TestAuth.login("lcampos", "Sales1234"))
        .when()
            .get("/clients/" + id)
        .then()
            .statusCode(200);
    }

    @Test
    void get_withDispatcherRole_returns200() {
        Integer id = seedClient(NAME_PREFIX + "DISPATCHER", nextRuc());

        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken("dispatcher_test", "dispatcher"))
        .when()
            .get("/clients/" + id)
        .then()
            .statusCode(200);
    }

    // ---------- PUT: happy path ----------------------------------------------

    @Test
    void update_withAllFourFieldsChanged_returns200AndPersists() {
        Integer id = seedClient(NAME_PREFIX + "BASE", nextRuc());
        String rucNuevo = nextRuc();
        String token = TestAuth.adminToken();

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(NAME_PREFIX + "NUEVO", rucNuevo, "987654321", "Ana Ríos"))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("id", equalTo(id))
            .body("name", equalTo(NAME_PREFIX + "NUEVO"))
            .body("ruc", equalTo(rucNuevo))
            .body("phone", equalTo("987654321"))
            .body("contactName", equalTo("Ana Ríos"));

        // Re-lectura: que la respuesta sea correcta no prueba que se haya guardado.
        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients/" + id)
        .then()
            .statusCode(200)
            .body("name", equalTo(NAME_PREFIX + "NUEVO"))
            .body("ruc", equalTo(rucNuevo))
            .body("phone", equalTo("987654321"))
            .body("contactName", equalTo("Ana Ríos"));
    }

    /**
     * El caso que obliga a excluir el propio id en las dos consultas de unicidad: sin eso, el
     * chequeo se encuentra a sí mismo y guardar sin cambiar nada devolvería 409.
     */
    @Test
    void update_withOwnRucAndOwnName_returns200_notConflict() {
        String ruc = nextRuc();
        String nombre = NAME_PREFIX + "PROPIO";
        Integer id = seedClient(nombre, ruc);

        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body(body(nombre, ruc, "912345678", null))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(200)
            .body("phone", equalTo("912345678"));
    }

    /**
     * Editar no cambia isActive ni createdAt. El cliente se siembra INACTIVO a propósito: con
     * uno activo, un mapper que escribiera isActive=true pasaría igual y el caso no mediría nada.
     */
    @Test
    void update_onInactiveClient_keepsIsActiveAndCreatedAt() {
        Integer id = seedClient(NAME_PREFIX + "RN06", nextRuc(), false);
        String token = TestAuth.adminToken();

        String createdAtAntes = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients/" + id)
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("createdAt");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(NAME_PREFIX + "RN06_EDITADO", nextRuc(), "987654321", "Editor"))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(200)
            .body("isActive", equalTo(false))
            .body("createdAt", equalTo(createdAtAntes));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients/" + id)
        .then()
            .body("isActive", equalTo(false))
            .body("createdAt", equalTo(createdAtAntes));
    }

    // ---------- PUT: normalización -------------------------------------------

    @Test
    void update_trimsAndUppercasesName_andTrimsContactName() {
        Integer id = seedClient(NAME_PREFIX + "NORM", nextRuc());

        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body(body("  " + NAME_PREFIX.toLowerCase() + "norm_edit  ", nextRuc(), null, "  María  "))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(200)
            .body("name", equalTo(NAME_PREFIX + "NORM_EDIT"))
            .body("contactName", equalTo("María"));
    }

    @Test
    void update_withBlankContactName_persistsAsNull() {
        Integer id = seedClient(NAME_PREFIX + "BLANK", nextRuc());

        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body(body(NAME_PREFIX + "BLANK", nextRuc(), null, "   "))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(200)
            .body("contactName", nullValue());
    }

    /** El PUT REEMPLAZA: omitir los opcionales los deja en nulo, no conserva los viejos. */
    @Test
    void update_omittingOptionals_setsThemNull() {
        String ruc = nextRuc();
        Integer id = QuarkusTransaction.requiringNew().call(() -> {
            Client client = new Client();
            client.name = NAME_PREFIX + "REEMPLAZO";
            client.ruc = ruc;
            client.phone = "999888777";
            client.contactName = "Contacto Viejo";
            client.isActive = true;
            clientRepository.persist(client);
            return client.id;
        });
        String token = TestAuth.adminToken();

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(NAME_PREFIX + "REEMPLAZO", ruc, null, null))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(200)
            .body("phone", nullValue())
            .body("contactName", nullValue());

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients/" + id)
        .then()
            .body("phone", nullValue())
            .body("contactName", nullValue());
    }

    // ---------- PUT: 400 ------------------------------------------------------

    /**
     * Las cinco columnas se ligan a cinco parámetros. Ojo con esto: si la firma tiene menos
     * parámetros que columnas, JUnit descarta las de más EN SILENCIO y el parámetro se queda con
     * la columna equivocada. Acá el `campo` se usa además para nombrar la fixture y para afirmar
     * qué campo reporta el error, así que cada fila mide lo suyo y una regresión dice cuál es.
     */
    @ParameterizedTest(name = "{4}")
    @CsvSource(value = {
        "NULL | 91999999001  | null       | name  | name ausente",
        "X    | 9199999900   | null       | ruc   | ruc de 10 digitos",
        "X    | 919999990012 | null       | ruc   | ruc de 12 digitos",
        "X    | 9199999900A  | null       | ruc   | ruc con letra",
        "X    | 91999999002  | 12345678   | phone | phone de 8 digitos",
        "X    | 91999999003  | 1234567890 | phone | phone de 10 digitos",
        "X    | 91999999004  | VACIO      | phone | phone vacio",
    }, delimiter = '|')
    void update_withInvalidField_returns400_COM001(
            String name, String ruc, String phone, String campo, String descripcion) {
        Integer id = seedClient(NAME_PREFIX + "INVALIDO_" + campo.trim(), nextRuc());
        String nombreReal = "NULL".equals(name.trim()) ? null : NAME_PREFIX + "VALIDO";
        // Tres formas distintas de "no hay telefono valido", y las tres tienen que ser 400:
        // ausente (null), corto/largo, y la cadena vacia, que el contrato nombra aparte.
        String phoneReal = switch (phone.trim()) {
            case "null" -> null;
            case "VACIO" -> "";
            default -> phone.trim();
        };

        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body(body(nombreReal, ruc.trim(), phoneReal, null))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem(campo.trim()));
    }

    @Test
    void update_withNameTooLong_returns400_COM001() {
        Integer id = seedClient(NAME_PREFIX + "LARGO", nextRuc());

        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body(body("A".repeat(201), nextRuc(), null, null))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("name"));
    }

    @Test
    void update_withContactNameTooLong_returns400_COM001() {
        Integer id = seedClient(NAME_PREFIX + "CONTACTOLARGO", nextRuc());

        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body(body(NAME_PREFIX + "CONTACTOLARGO", nextRuc(), null, "A".repeat(101)))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("contactName"));
    }

    /** El Problem de validación nombra el campo, que es lo que el formulario necesita. */
    @Test
    void update_withMissingName_reportsTheFieldInErrors() {
        Integer id = seedClient(NAME_PREFIX + "SINNOMBRE", nextRuc());

        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body(body(null, nextRuc(), null, null))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("name"));
    }

    /**
     * Regla 6 del proyecto: el cuerpo ausente se rechaza en el BORDE, con `@NotNull`.
     *
     * <p>No alcanza con afirmar el 400: medido el 2026-09-14, quitar el `@NotNull` deja el mismo
     * 400 con el mismo COM-001, porque el mapper devuelve un command de nulos y la guarda del
     * servicio lo atrapa. Lo que distingue a las dos capas es el detalle: el borde adjunta la
     * violación de Bean Validation en `errors`, y la guarda del servicio no adjunta nada. Sin
     * esta segunda aserción, borrar el `@NotNull` no rompe ninguna prueba.
     *
     * <p>Se afirma el CAMPO y no el mensaje: el mensaje por defecto de Bean Validation se
     * interpola con el locale de la JVM, y la suite no lo fija. Afirmar "must not be null" pone
     * la rama en rojo en una máquina configurada en español, por algo que no cambió.
     */
    @Test
    void update_withEmptyBody_returns400_COM001_rejectedAtTheBorder() {
        Integer id = seedClient(NAME_PREFIX + "VACIO", nextRuc());

        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors", notNullValue())
            .body("errors.field", hasItem("updateClient.clientRequest"));
    }

    /** Mismo criterio que el cuerpo ausente: se afirma QUIÉN rechaza, no solo el estado. */
    @Test
    void update_withExplicitNullBody_returns400_COM001_rejectedAtTheBorder() {
        Integer id = seedClient(NAME_PREFIX + "NULO", nextRuc());

        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body("null")
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors", notNullValue())
            .body("errors.field", hasItem("updateClient.clientRequest"));
    }

    /**
     * Una razón social de solo espacios es 400 aunque el esquema declare un mínimo de 1
     * carácter, y lo rechaza el `@NotBlank` del request, en el borde, antes de buscar al
     * cliente.
     *
     * <p>Por eso se afirma el campo y no solo el código: medido el 2026-09-14, degradar ese
     * `@NotBlank` a `@NotNull` deja el MISMO 400 con el MISMO COM-001, porque el mapper
     * normaliza "   " a null y la guarda del servicio lo atrapa. Lo que se pierde en silencio es
     * el `errors[]` que el formulario necesita para marcar el campo.
     */
    @Test
    void update_withNameOnlyWhitespace_returns400_COM001() {
        Integer id = seedClient(NAME_PREFIX + "ESPACIOS", nextRuc());

        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body(body("   ", nextRuc(), null, null))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"))
            .body("errors.field", hasItem("name"));
    }

    /**
     * El contrato dice que para dejar a un cliente sin teléfono "se omite el campo o se manda
     * `null`". El ayudante de cuerpos omite lo que recibe nulo, así que la rama del `null`
     * EXPLÍCITO no se puede expresar con él: este caso manda el JSON a mano.
     */
    @Test
    void update_withExplicitJsonNulls_clearsTheOptionalFields() {
        String nombre = NAME_PREFIX + "NULOS";
        String ruc = nextRuc();
        Integer id = seedClient(nombre, ruc);
        String token = TestAuth.adminToken();

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(nombre, ruc, "987654321", "Ana"))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(200)
            .body("phone", equalTo("987654321"));

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{\"name\":\"" + nombre + "\",\"ruc\":\"" + ruc + "\","
                + "\"phone\":null,\"contactName\":null}")
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(200)
            .body("phone", nullValue())
            .body("contactName", nullValue());
    }

    // ---------- PUT: 409 ------------------------------------------------------

    @Test
    void update_toAnotherClientsRuc_returns409_CLI001() {
        String rucDeA = nextRuc();
        seedClient(NAME_PREFIX + "CONFLICTO_A", rucDeA);
        String nombreDeB = NAME_PREFIX + "CONFLICTO_B";
        String rucDeB = nextRuc();
        Integer idB = seedClient(nombreDeB, rucDeB);

        String token = TestAuth.adminToken();

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(nombreDeB, rucDeA, null, null))
        .when()
            .put("/clients/" + idB)
        .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("CLI-001"));

        // El 409 no deja rastro. Este caso sale por el chequeo previo, donde la entidad todavia
        // no se toco; la relectura existe para que, si alguien moviera ese chequeo despues de
        // aplicar los campos, el rastro apareciera aca.
        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients/" + idB)
        .then()
            .statusCode(200)
            .body("ruc", equalTo(rucDeB));
    }

    /**
     * Con el RUC Y la razón social de otro cliente a la vez gana CLI-001, y la fila no cambia.
     *
     * <p>Es el único caso que distingue CUÁL de los dos chequeos previos miró qué campo: medido
     * el 2026-09-14, cambiar el campo que consulta la consulta del RUC por el de la razón social
     * deja verde a toda la suite, porque los otros dos casos de 409 tienen un solo conflicto y
     * el resultado HTTP no dice quién lo atrapó. Acá sí: con la consulta cruzada, la base
     * responde por la restricción que toca primero y sale CLI-002. El formulario mapea CLI-001
     * al RUC y CLI-002 a la razón social, así que señalaría el campo equivocado.
     */
    @Test
    void update_toAnotherClientsRucAndName_returns409_CLI001_andLeavesTheRowIntact() {
        String rucDeA = nextRuc();
        String nombreDeA = NAME_PREFIX + "DOBLE_A";
        seedClient(nombreDeA, rucDeA);
        String rucDeB = nextRuc();
        String nombreDeB = NAME_PREFIX + "DOBLE_B";
        Integer idB = seedClient(nombreDeB, rucDeB);
        String token = TestAuth.adminToken();

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(nombreDeA, rucDeA, null, null))
        .when()
            .put("/clients/" + idB)
        .then()
            .statusCode(409)
            .contentType("application/problem+json")
            .body("code", equalTo("CLI-001"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients/" + idB)
        .then()
            .statusCode(200)
            .body("name", equalTo(nombreDeB))
            .body("ruc", equalTo(rucDeB));
    }

    /**
     * La razón social se compara YA NORMALIZADA. Se manda en minúsculas a propósito: si alguien
     * quitara el uppercase antes del chequeo, el duplicado no se detectaría y esto daría 200.
     */
    @Test
    void update_toAnotherClientsNameInLowercase_returns409_CLI002() {
        String nombreDeA = NAME_PREFIX + "NOMBRE_A";
        seedClient(nombreDeA, nextRuc());
        String rucDeB = nextRuc();
        String nombreDeB = NAME_PREFIX + "NOMBRE_B";
        Integer idB = seedClient(nombreDeB, rucDeB);
        String token = TestAuth.adminToken();

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(nombreDeA.toLowerCase(), rucDeB, null, null))
        .when()
            .put("/clients/" + idB)
        .then()
            .statusCode(409)
            .body("code", equalTo("CLI-002"));

        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients/" + idB)
        .then()
            .statusCode(200)
            .body("name", equalTo(nombreDeB));
    }

    // ---------- PUT: 404 ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"999999999", "0", "-1"})
    void update_nonexistentOrZeroOrNegativeId_returns404_CLI003(String id) {
        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body(body(NAME_PREFIX + "FANTASMA", nextRuc(), null, null))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("code", equalTo("CLI-003"));
    }

    /** Cuerpo VÁLIDO a propósito: lo que se mide es el 404 del conversor, no una validación. */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "9999999999999999999999"})
    void update_idThatDoesNotFitAnInt_returns404WithoutBody(String id) {
        String cuerpo = given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body(body(NAME_PREFIX + "FANTASMA", nextRuc(), null, null))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(404)
            .extract().body().asString();

        assertTrue(cuerpo == null || cuerpo.isBlank(), "El 404 del id " + id + " trajo cuerpo: " + cuerpo);
    }

    // ---------- PUT: roles ----------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"sales", "dispatcher", "finance_manager", "warehouse_keeper"})
    void update_withRoleOutsideTheList_returns403_COM003(String role) {
        Integer id = seedClient(NAME_PREFIX + "ROL_" + role.toUpperCase(), nextRuc());

        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken(role + "_test", role))
            .contentType(ContentType.JSON)
            .body(body(NAME_PREFIX + "ROL_" + role.toUpperCase(), nextRuc(), null, null))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(403)
            .contentType("application/problem+json")
            .body("code", equalTo("COM-003"));
    }

    /** Los otros dos roles de la lista, que el happy path (admin) no cubre. */
    @ParameterizedTest
    @ValueSource(strings = {"general_manager", "operations_manager"})
    void update_withAllowedRole_returns200(String role) {
        String nombre = NAME_PREFIX + "OK_" + role.toUpperCase();
        String ruc = nextRuc();
        Integer id = seedClient(nombre, ruc);

        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken(role + "_test", role))
            .contentType(ContentType.JSON)
            .body(body(nombre, ruc, "955555555", null))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(200)
            .body("phone", equalTo("955555555"));
    }

    @Test
    void update_withoutToken_returns401() {
        Integer id = seedClient(NAME_PREFIX + "PUTSINTOKEN", nextRuc());

        given()
            .contentType(ContentType.JSON)
            .body(body(NAME_PREFIX + "PUTSINTOKEN", nextRuc(), null, null))
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(401);
    }

    // ---------- PUT: el orden de evaluación -----------------------------------
    // Tres casos, uno por par adyacente observable. El par 401 → 403 no necesita caso: sin
    // token no hay identidad que evaluar, y update_withoutToken_returns401 ya lo fija.

    /** 403 antes que 400: rol prohibido Y cuerpo inválido a la vez. */
    @Test
    void update_withForbiddenRole_andInvalidBody_returns403_COM003() {
        Integer id = seedClient(NAME_PREFIX + "ORDEN_403", nextRuc());

        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken("sales_test", "sales"))
            .contentType(ContentType.JSON)
            .body("{\"name\":\"\",\"ruc\":\"abc\"}")
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    /**
     * El 403 precede al 404 del conversor: rol prohibido Y un id que no encaja en un entero.
     *
     * <p>Es la mitad específica de este endpoint de la frase del contrato "el 404 del id que no
     * encaja se intercala entre el 403 y el 400". El otro eslabón lo mide el caso de abajo.
     */
    @Test
    void update_withForbiddenRole_andIdThatDoesNotFitAnInt_returns403_COM003() {
        given()
            .header("Authorization", "Bearer " + TestAuth.fabricateAccessToken("sales_test", "sales"))
            .contentType(ContentType.JSON)
            .body(body(NAME_PREFIX + "ORDEN_CONV", nextRuc(), null, null))
        .when()
            .put("/clients/abc")
        .then()
            .statusCode(403)
            .body("code", equalTo("COM-003"));
    }

    /** El 404 del conversor precede al 400 del cuerpo: id que no encaja Y cuerpo inválido. */
    @Test
    void update_withIdThatDoesNotFitAnInt_andInvalidBody_returns404WithoutBody() {
        String cuerpo = given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body("{\"name\":\"\",\"ruc\":\"abc\"}")
        .when()
            .put("/clients/abc")
        .then()
            .statusCode(404)
            .extract().body().asString();

        assertTrue(cuerpo == null || cuerpo.isBlank(),
            "El 404 del conversor trajo cuerpo, o sea que se evaluó el cuerpo antes: " + cuerpo);
    }

    /** 400 antes que 404: id inexistente Y cuerpo inválido a la vez. */
    @Test
    void update_nonexistentId_withInvalidBody_returns400_COM001() {
        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body("{\"name\":\"X\",\"ruc\":\"abc\"}")
        .when()
            .put("/clients/999999999")
        .then()
            .statusCode(400)
            .body("code", equalTo("COM-001"));
    }

    /** 404 antes que 409: id inexistente Y el RUC de otro cliente a la vez. */
    @Test
    void update_nonexistentId_withAnotherClientsRuc_returns404_CLI003() {
        String rucDeA = nextRuc();
        String nombreDeA = NAME_PREFIX + "ORDEN_409_A";
        seedClient(nombreDeA, rucDeA);

        given()
            .header("Authorization", "Bearer " + TestAuth.adminToken())
            .contentType(ContentType.JSON)
            .body(body(nombreDeA, rucDeA, null, null))
        .when()
            .put("/clients/999999999")
        .then()
            .statusCode(404)
            .body("code", equalTo("CLI-003"));
    }

    // ---------- Campos que el cuerpo no puede escribir ------------------------

    /**
     * Un cuerpo que TRAE `id`, `isActive` y `createdAt` no los escribe: son del servidor.
     *
     * <p>El caso del cliente inactivo mide que el mapeo no los toque; este mide lo otro, que es
     * lo que un atacante intentaría: mandarlos igual. Hoy la invariante se sostiene porque el
     * request no tiene esos componentes y el resto se descarta, pero eso no lo fijaba ningún
     * caso: el día que alguien agregue `isActive` al request para reusarlo en otra pantalla,
     * esto se pone rojo en vez de reactivar clientes en silencio.
     */
    @Test
    void update_withServerOwnedFieldsInTheBody_ignoresThem() {
        Integer idVecino = seedClient(NAME_PREFIX + "MASS_VECINO", nextRuc());
        Integer id = seedClient(NAME_PREFIX + "MASS", nextRuc(), false);
        String token = TestAuth.adminToken();

        String createdAtAntes = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients/" + id)
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("createdAt");

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body("{\"name\":\"" + NAME_PREFIX + "MASS_EDIT\",\"ruc\":\"" + nextRuc() + "\","
                + "\"id\":" + idVecino + ",\"isActive\":true,\"createdAt\":\"1999-01-01T00:00:00Z\"}")
        .when()
            .put("/clients/" + id)
        .then()
            .statusCode(200)
            .body("id", equalTo(id))
            .body("isActive", equalTo(false))
            .body("createdAt", equalTo(createdAtAntes));

        // Y el vecino cuyo id viajaba en el cuerpo sigue como estaba.
        given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients/" + idVecino)
        .then()
            .statusCode(200)
            .body("name", equalTo(NAME_PREFIX + "MASS_VECINO"));
    }

    /**
     * Editar un cliente NO puede resucitar a uno que alguien desactivó mientras tanto.
     *
     * <p>Este caso baja a la capa de persistencia porque la regla no se puede ver desde HTTP: un
     * PUT es una sola transacción y, con un único escritor, el valor que se relee es el mismo que
     * se escribió. El defecto solo aparece cuando OTRA transacción cambia `isActive` entre que la
     * edición carga la fila y la descarga.
     *
     * <p>Lo que protege es el `updatable = false` de la columna. El UPDATE de una edición que
     * cambia algo lleva todas las columnas actualizables, así que sin eso llevaría `is_active`
     * con el valor que se
     * leyó al abrir; los `ignore` del mapper no alcanzan, porque protegen el campo en memoria y
     * no la columna. La otra transacción desactiva con SQL a propósito: por la entidad no podría,
     * que es justamente el punto.
     *
     * <p>Hoy ningún código de producción escribe esa columna: el segundo escritor va a ser el
     * endpoint de desactivación, que ya está planificado. Este caso existe para que ese día el
     * defecto salga acá y no en producción.
     *
     * <p>Pasa POR {@code updateClient} y no por una escritura a mano: lo que hay que proteger es
     * el camino del endpoint. Con una mutación de la entidad seguiría verde el día que la
     * edición cambie de forma de escribir, que es justo el día que importa.
     *
     * <p>Y afirma las DOS cosas, la fila y la respuesta. Son distintas: la columna no viaja en el
     * UPDATE, así que en memoria sigue valiendo lo que valía al cargar y sin la relectura de la
     * fila el cuerpo del 200 diría que el cliente está activo mientras la base lo tiene
     * desactivado.
     */
    @Test
    void update_doesNotResurrectAClientDeactivatedMeanwhile() {
        String nombre = NAME_PREFIX + "CARRERA";
        String ruc = nextRuc();
        Integer id = seedClient(nombre, ruc, true);

        boolean[] laRespuestaDiceActivo = new boolean[1];
        QuarkusTransaction.requiringNew().run(() -> {
            // La edición ya cargó la fila: en memoria, el cliente todavía está activo.
            Client enEdicion = clientRepository.findById(id);
            assertTrue(enEdicion.isActive, "la fixture tiene que arrancar activa");

            // Otra transacción lo desactiva y commitea, en el medio.
            QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery("UPDATE clients SET is_active = false WHERE id = ?1")
                    .setParameter(1, id)
                    .executeUpdate()
            );

            ClientResponse respuesta = clientService.updateClient(
                id, new UpdateClientCommand(nombre, ruc, "955555555", null));
            laRespuestaDiceActivo[0] = Boolean.TRUE.equals(respuesta.isActive());
        });

        boolean sigueDesactivado = QuarkusTransaction.requiringNew().call(() ->
            ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM clients WHERE id = ?1 AND is_active = false")
                .setParameter(1, id)
                .getSingleResult()).intValue() == 1
        );
        assertTrue(sigueDesactivado, "La edición reescribió is_active y resucitó al cliente");
        assertFalse(laRespuestaDiceActivo[0],
            "La fila quedó desactivada pero el 200 respondió isActive=true");
    }

    /**
     * Una edición QUE CAMBIA ALGO pisa lo que otra escribió en el medio, incluso en las columnas
     * que el cuerpo trae iguales a como se cargaron. Es la razón de ser de que la entidad NO
     * lleve escritura dinámica: con ella, el UPDATE llevaría solo las columnas sucias, el
     * teléfono viejo del cuerpo no viajaría y ganaría la escritura ANTERIOR, que es lo contrario
     * de lo que las dos specs prometen.
     *
     * <p>Baja al service por el mismo motivo que el caso de la resurrección: por HTTP no se
     * pueden intercalar dos transacciones.
     */
    @Test
    void update_thatChangesAField_alsoOverwritesTheFieldsThatArrivedUnchanged() {
        String nombre = NAME_PREFIX + "PISA";
        String ruc = nextRuc();
        Integer id = seedClient(nombre, ruc);
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery("UPDATE clients SET phone = ?1 WHERE id = ?2")
                .setParameter(1, "911111111").setParameter(2, id).executeUpdate());

        QuarkusTransaction.requiringNew().run(() -> {
            clientRepository.findById(id);

            // Otra transacción le cambia el teléfono y commitea, en el medio.
            QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery("UPDATE clients SET phone = ?1 WHERE id = ?2")
                    .setParameter(1, "999999999").setParameter(2, id).executeUpdate());

            // El cuerpo trae el teléfono VIEJO (el que el formulario cargó) y un contacto nuevo.
            clientService.updateClient(id, new UpdateClientCommand(nombre, ruc, "911111111", "NUEVO"));
        });

        String phoneFinal = QuarkusTransaction.requiringNew().call(() ->
            (String) entityManager
                .createNativeQuery("SELECT phone FROM clients WHERE id = ?1")
                .setParameter(1, id).getSingleResult());

        assertEquals("911111111", phoneFinal,
            "el UPDATE no llevó el telefono que mandó el cuerpo: gano la escritura anterior");
    }

    /**
     * La excepción honesta de "gana la última escritura": una edición que no cambia NINGÚN campo
     * no emite UPDATE, así que no desplaza lo que otro escribió en el medio.
     *
     * <p>No lo decide la escritura dinámica sino el control de cambios de Hibernate, que es una
     * decisión anterior: si la entidad no quedó sucia, no hay sentencia. Este caso existe para
     * que la excepción esté medida y no se descubra en producción.
     */
    @Test
    void update_thatChangesNothing_doesNotDisplaceAConcurrentWrite() {
        String nombre = NAME_PREFIX + "SINCAMBIOS";
        String ruc = nextRuc();
        Integer id = seedClient(nombre, ruc);
        QuarkusTransaction.requiringNew().run(() ->
            entityManager.createNativeQuery("UPDATE clients SET phone = ?1 WHERE id = ?2")
                .setParameter(1, "911111111").setParameter(2, id).executeUpdate());

        QuarkusTransaction.requiringNew().run(() -> {
            clientRepository.findById(id);
            QuarkusTransaction.requiringNew().run(() ->
                entityManager.createNativeQuery("UPDATE clients SET phone = ?1 WHERE id = ?2")
                    .setParameter(1, "999999999").setParameter(2, id).executeUpdate());

            // Los cuatro campos llegan IGUALES a como se cargaron.
            clientService.updateClient(id, new UpdateClientCommand(nombre, ruc, "911111111", null));
        });

        String phoneFinal = QuarkusTransaction.requiringNew().call(() ->
            (String) entityManager
                .createNativeQuery("SELECT phone FROM clients WHERE id = ?1")
                .setParameter(1, id).getSingleResult());

        assertEquals("999999999", phoneFinal,
            "una edicion que no cambia nada no deberia emitir UPDATE, asi que el valor ajeno queda");
    }

    /**
     * Fija los nombres de las restricciones que el servicio traduce a CLI-001 y CLI-002.
     *
     * <p>El servicio compara por nombre exacto, así que esos dos literales son un acoplamiento
     * con el esquema. Este caso lo mide contra la base de verdad: si una migración renombra una
     * restricción, el 409 se degradaría a 500 y sin este test nadie se entera hasta producción.
     */
    @ParameterizedTest(name = "{1}")
    @CsvSource({
        "ruc,  clients_ruc_key",
        "name, clients_name_key",
    })
    void theDatabaseReportsTheConstraintNamesThatTheServiceTranslates(
            String campo, String restriccionEsperada) {
        String nombre = NAME_PREFIX + "RESTRICCION_" + campo.toUpperCase();
        String ruc = nextRuc();
        seedClient(nombre, ruc);

        RuntimeException capturada = assertThrows(RuntimeException.class, () ->
            QuarkusTransaction.requiringNew().run(() -> {
                Client duplicado = new Client();
                duplicado.name = "name".equals(campo) ? nombre : NAME_PREFIX + "OTRO";
                duplicado.ruc = "ruc".equals(campo) ? ruc : nextRuc();
                duplicado.isActive = true;
                clientRepository.persist(duplicado);
                clientRepository.flush();
            }));

        // Se recorre la cadena entera en vez de asumir cómo la envuelve el proveedor: lo que se
        // mide es el nombre que reporta la BASE, no la forma del wrapper.
        String restriccionReportada = null;
        StringBuilder cadena = new StringBuilder();
        for (Throwable t = capturada; t != null && t != t.getCause(); t = t.getCause()) {
            cadena.append(t.getClass().getName()).append(" -> ");
            if (t instanceof ConstraintViolationException violacion) {
                restriccionReportada = violacion.getConstraintName();
                break;
            }
        }

        assertEquals(restriccionEsperada, restriccionReportada,
            "El nombre que reporta la base dejó de ser el que el servicio compara. Cadena: " + cadena);
    }

    /**
     * Editar la razón social o el RUC cambia lo que muestran las cotizaciones YA EMITIDAS.
     *
     * <p>El contrato lo promete con esas palabras, y hasta acá nada lo medía. Hoy es cierto
     * porque la cotización guarda solo el id del cliente y resuelve el nombre y el RUC al leer;
     * el precedente contrario está en la misma tabla, donde el contacto y su teléfono SÍ son
     * copia congelada del momento de cotizar. El día que alguien congele también el nombre, la
     * reimpresión de un documento viejo dejaría de reflejar la corrección y este caso lo dice.
     *
     * <p>La cotización se borra por id en el {@code finally}: la limpieza de la clase borra
     * clientes por prefijo de RUC y una cotización que los referencie la haría fallar por clave
     * foránea, que es la forma más rápida de dejar la base compartida inservible para la corrida
     * siguiente.
     */
    @Test
    void update_changesWhatAnAlreadyIssuedQuotationShows() {
        String nombreViejo = NAME_PREFIX + "EMITIDA";
        String rucViejo = nextRuc();
        Integer idCliente = seedClient(nombreViejo, rucViejo);
        String token = TestAuth.adminToken();
        int cargoTypeId = fixtures.seedCargoType();
        Integer idCotizacion = null;

        try {
            idCotizacion = given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(quotationBody(idCliente, cargoTypeId))
            .when()
                .post("/quotations")
            .then()
                .statusCode(201)
                .extract().path("id");

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/quotations/" + idCotizacion)
            .then()
                .statusCode(200)
                .body("client.name", equalTo(nombreViejo))
                .body("client.ruc", equalTo(rucViejo));

            String nombreNuevo = NAME_PREFIX + "EMITIDA_CORREGIDA";
            String rucNuevo = nextRuc();
            given()
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body(body(nombreNuevo, rucNuevo, null, null))
            .when()
                .put("/clients/" + idCliente)
            .then()
                .statusCode(200);

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/quotations/" + idCotizacion)
            .then()
                .statusCode(200)
                .body("client.name", equalTo(nombreNuevo))
                .body("client.ruc", equalTo(rucNuevo));
        } finally {
            Integer aBorrar = idCotizacion;
            QuarkusTransaction.requiringNew().run(() -> {
                if (aBorrar != null) {
                    // La cascada de la base se lleva items y condiciones enlazadas.
                    entityManager.createNativeQuery(
                        "DELETE FROM cotizaciones.quotations WHERE id = ?1")
                        .setParameter(1, aBorrar).executeUpdate();
                }
                entityManager.createNativeQuery("DELETE FROM public.cargo_types WHERE id = ?1")
                    .setParameter(1, cargoTypeId).executeUpdate();
            });
        }
    }

    /** Cuerpo mínimo de una cotización válida, con los catálogos resueltos por clave natural. */
    private String quotationBody(Integer clientId, int cargoTypeId) {
        return String.format("""
            {
              "quotationType": "TRANSPORTE",
              "clientId": %d,
              "contactName": "ZBYID_CONTACTO",
              "currencyId": %d,
              "paymentTermId": %d,
              "validityDays": 15,
              "origin": "ZBYID_LIMA",
              "destination": "ZBYID_AREQUIPA",
              "items": [
                { "serviceTypeId": %d, "cargoTypeId": %d, "weightKg": 10.00, "quantity": 1, "unitPrice": 1000.00 }
              ]
            }
            """, clientId, fixtures.currencyId("USD"), fixtures.paymentTermId("Contado"),
            fixtures.serviceTypeId("SCB"), cargoTypeId);
    }

    // ---------- La edición no toca a los demás --------------------------------

    /** Guarda de alcance: editar un cliente no puede escribir sobre otro. */
    @Test
    void update_doesNotTouchAnotherClient() {
        String nombreVecino = NAME_PREFIX + "VECINO";
        String rucVecino = nextRuc();
        Integer idVecino = seedClient(nombreVecino, rucVecino);
        Integer idEditado = seedClient(NAME_PREFIX + "EDITADO", nextRuc());
        String token = TestAuth.adminToken();

        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(body(NAME_PREFIX + "EDITADO_2", nextRuc(), "944444444", "Otro"))
        .when()
            .put("/clients/" + idEditado)
        .then()
            .statusCode(200);

        String nombreDespues = given()
            .header("Authorization", "Bearer " + token)
        .when()
            .get("/clients/" + idVecino)
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("name");

        assertEquals(nombreVecino, nombreDespues, "La edición de un cliente pisó a otro");
    }
}
