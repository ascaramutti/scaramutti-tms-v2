package com.scaramutti.tms.clients;

import com.scaramutti.tms.shared.entity.Client;
import com.scaramutti.tms.shared.repository.ClientRepository;
import com.scaramutti.tms.support.TestAuth;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /clients/{id}}.
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
        QuarkusTransaction.requiringNew().run(() ->
            clientRepository.delete("ruc like ?1", RUC_PREFIX + "%")
        );
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
}
