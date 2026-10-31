package com.scaramutti.tms.auth;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.scaramutti.tms.support.LockWaiters.UNTIL_IT_ARRIVES_MILLIS;
import static com.scaramutti.tms.support.LockWaiters.WHILE_ANOTHER_WAITS_MILLIS;
import static com.scaramutti.tms.support.LockWaiters.awaitWaiters;
import static com.scaramutti.tms.support.LockWaiters.backendPid;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El cambio de la propia contrasena verifica la actual sin la fila y, si verifica, la toma antes de
 * cargar la cuenta. Una conexion la sostiene y el test mira que el pedido quede esperando DETRAS de
 * ella antes de soltarla (con la actual equivocada, que no la espere); el pedido corre en otro hilo
 * con un plazo, asi que si alguien le saca el tope el caso falla en vez de colgar la suite.
 */
@QuarkusTest
class OwnAccountRowLockResourceTest {

    private static final String USERNAME = "ztestuserfila1";
    private static final String PASSWORD = "Sales1234";

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;
    @Inject DataSource dataSource;

    private int userId;
    private ExecutorService executor;

    @BeforeEach
    void seedAccount() {
        cleanupFixtures();
        int workerId = fixtures.seedWorker("ZTESTFILA1", "Fila", "Cuenta", "sales", true);
        userId = fixtures.seedUserFor(workerId, USERNAME, "sales");
        fixtures.copyPasswordHashFrom(userId, "sales");
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stopTheRequestThenCleanup() throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            System.err.println("AVISO: quedo vivo un pedido de " + getClass().getSimpleName());
        }
        cleanupFixtures();
    }

    private void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    /**
     * Otra escritura le cambia el hash mientras el cambio espera la fila: al soltarla, el hash
     * confirmado ya no es el que se verifico, y el de la otra queda. Sin comparar contra la fila
     * confirmada, este cambio pisaria al otro con un 200.
     */
    @Test
    void aWriterThatChangesTheHashWhileTheChangeWaits_makesTheCurrentStopVerifying() throws Exception {
        String accessToken = accessToken();
        String otherHash = passwordHashOf("admin");
        try (Connection holder = dataSource.getConnection(); Connection observer = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            Future<Response> change = null;
            try {
                onTheAccount(holder, "UPDATE public.users SET password_hash = ? WHERE id = ?", otherHash);
                change = executor.submit(() -> changePassword(accessToken));

                assertTrue(awaitWaiters(observer, backendPid(holder), 1, change, UNTIL_IT_ARRIVES_MILLIS),
                    "el cambio tiene que estar esperando la fila antes de soltarla");
                holder.commit();

                change.get(10, TimeUnit.SECONDS).then()
                    .statusCode(400)
                    .body("code", equalTo("AUTH-004"));
                assertEquals(otherHash, passwordHashOf(userId));
                assertEquals(0, sessionVersionOf(userId));
            } finally {
                release(holder, change);
            }
        }
    }

    /** Retenida mas alla del tope: el conflicto del contrato, nunca un 500, y nada cambia. */
    @Test
    void withTheRowHeldPastTheTimeout_returns409AccountBusy_andChangesNothing() throws Exception {
        String accessToken = accessToken();
        String hashBefore = passwordHashOf(userId);
        try (Connection holder = dataSource.getConnection(); Connection observer = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            Future<Response> change = null;
            try {
                onTheAccount(holder, "SELECT id FROM public.users WHERE id = ? FOR NO KEY UPDATE", null);
                change = executor.submit(() -> changePassword(accessToken));

                assertTrue(awaitWaiters(observer, backendPid(holder), 1, change, UNTIL_IT_ARRIVES_MILLIS),
                    "el cambio tiene que estar esperando la fila");
                change.get(10, TimeUnit.SECONDS).then()
                    .statusCode(409)
                    .body("code", equalTo("AUTH-010"))
                    .body("title", equalTo("Account busy"))
                    .body("detail", equalTo("La cuenta está siendo modificada por otra operación"))
                    .body("instance", equalTo("/auth/change-password"));
            } finally {
                release(holder, change);
            }
        }
        assertEquals(hashBefore, passwordHashOf(userId));
        assertEquals(0, sessionVersionOf(userId));
    }

    /**
     * Con la actual equivocada no hay nada que escribir y la fila no se toma: una tanda de esos
     * pedidos no frena a quien si la escribe (la baja de su trabajador, mas adelante restablecer).
     * Con la fila retenida por otro, responde AUTH-004 sin esperarla.
     */
    @Test
    void aChangeWithTheWrongCurrent_answersWithoutWaitingForTheRow() throws Exception {
        String accessToken = accessToken();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                onTheAccount(holder, "SELECT id FROM public.users WHERE id = ? FOR NO KEY UPDATE", null);

                executor.submit(() -> changePassword(accessToken, "Equivocada99")).get(10, TimeUnit.SECONDS).then()
                    .statusCode(400)
                    .body("code", equalTo("AUTH-004"));
            } finally {
                holder.rollback();
            }
        }
    }

    /**
     * Dos cambios a la vez, con la misma actual: el segundo espera al primero y, con la fila ya
     * confirmada, la actual no verifica. Un lock que dejara pasar a los dos los haria chocar al
     * escribir, y uno saldria como el conflicto, que invita a reintentar.
     */
    @Test
    void twoChangesAtOnce_theSecondFindsTheCurrentAlreadyChanged() throws Exception {
        String accessToken = accessToken();
        ExecutorService both = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection(); Connection observer = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            List<Future<Response>> changes = new ArrayList<>();
            try {
                onTheAccount(holder, "SELECT id FROM public.users WHERE id = ? FOR NO KEY UPDATE", null);
                changes.add(both.submit(() -> changePassword(accessToken)));
                changes.add(both.submit(() -> changePassword(accessToken)));
                int holderPid = backendPid(holder);

                assertTrue(awaitWaiters(observer, holderPid, 1, changes.get(0), UNTIL_IT_ARRIVES_MILLIS)
                        && awaitWaiters(observer, holderPid, 2, changes.get(1), WHILE_ANOTHER_WAITS_MILLIS),
                    "los dos cambios tienen que estar esperando la fila");
                holder.rollback();

                List<String> outcomes = new ArrayList<>();
                for (Future<Response> change : changes) {
                    Response response = change.get(10, TimeUnit.SECONDS);
                    outcomes.add(response.statusCode() + " " + response.jsonPath().getString("code"));
                }
                outcomes.sort(null);
                assertEquals(List.of("200 null", "400 AUTH-004"), outcomes);
            } finally {
                holder.rollback();
                both.shutdownNow();
                both.awaitTermination(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(1, sessionVersionOf(userId));
    }

    /**
     * El UPDATE tambien puede esperar: la fila ya es suya, pero una migracion que toma la tabla en
     * SHARE ROW EXCLUSIVE (una clave foranea nueva hacia las cuentas) lo frena. Pasado el tope, el
     * conflicto del contrato y nada cambia; escrito recien al confirmar, salia como error del servidor.
     */
    @Test
    void withTheTableHeldByAMigrationAtTheUpdate_returns409AccountBusy_andChangesNothing() throws Exception {
        String accessToken = accessToken();
        String hashBefore = passwordHashOf(userId);
        try (Connection holder = dataSource.getConnection(); Connection observer = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            Future<Response> change = null;
            try {
                try (Statement statement = holder.createStatement()) {
                    statement.execute("LOCK TABLE public.users IN SHARE ROW EXCLUSIVE MODE");
                }
                change = executor.submit(() -> changePassword(accessToken));

                assertTrue(awaitWaiters(observer, backendPid(holder), 1, change, UNTIL_IT_ARRIVES_MILLIS),
                    "el cambio tiene que estar esperando la tabla");
                change.get(10, TimeUnit.SECONDS).then()
                    .statusCode(409)
                    .body("code", equalTo("AUTH-010"));
            } finally {
                release(holder, change);
            }
        }
        assertEquals(hashBefore, passwordHashOf(userId));
        assertEquals(0, sessionVersionOf(userId));
    }

    /**
     * El lock es el debil: no espera al {@code FOR KEY SHARE} que toma sobre la cuenta el alta de
     * cualquier fila que ella firma. Con {@code FOR UPDATE}, firmar algo y cambiar la clave a la vez
     * chocarian siempre.
     */
    @Test
    void aTransactionThatSignsARowWithTheAccount_doesNotBlockTheChange() throws Exception {
        String accessToken = accessToken();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                onTheAccount(holder, "SELECT id FROM public.users WHERE id = ? FOR KEY SHARE", null);

                executor.submit(() -> changePassword(accessToken)).get(10, TimeUnit.SECONDS).then()
                    .statusCode(200);
                assertEquals(1, sessionVersionOf(userId));
            } finally {
                holder.rollback();
            }
        }
    }

    // ---------- fixtures ----------------------------------------------------------

    /**
     * Suelta la fila y espera al pedido antes de la limpieza: sin tope, seguiria esperando y
     * escribiria sobre la cuenta despues de borrada. Se deshace antes de cerrar: el pool confirma
     * lo pendiente al devolver la conexion.
     */
    private static void release(Connection holder, Future<Response> change) throws Exception {
        holder.rollback();
        if (change != null && !change.isDone()) {
            try {
                change.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // El caso ya fallo por su lado; esto solo ordena la limpieza.
            }
        }
    }

    /** Una sentencia sobre la cuenta de prueba, con el valor opcional antes del id. */
    private void onTheAccount(Connection connection, String sql, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (value != null) {
                statement.setString(index++, value);
            }
            statement.setInt(index, userId);
            statement.execute();
        }
    }

    private int sessionVersionOf(int id) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(
                "SELECT session_version FROM public.users WHERE id = ?1")
            .setParameter(1, id).getSingleResult()).intValue());
    }

    private String passwordHashOf(int id) {
        return QuarkusTransaction.requiringNew().call(() -> (String) entityManager.createNativeQuery(
                "SELECT password_hash FROM public.users WHERE id = ?1")
            .setParameter(1, id).getSingleResult());
    }

    private String passwordHashOf(String username) {
        return QuarkusTransaction.requiringNew().call(() -> (String) entityManager.createNativeQuery(
                "SELECT password_hash FROM public.users WHERE username = ?1")
            .setParameter(1, username).getSingleResult());
    }

    private static String accessToken() {
        return given().contentType(ContentType.JSON)
            .body("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}")
            .when().post("/auth/login")
            .then().statusCode(200).extract().path("token");
    }

    private static Response changePassword(String accessToken) {
        return changePassword(accessToken, PASSWORD);
    }

    private static Response changePassword(String accessToken, String currentPassword) {
        return given().header("Authorization", "Bearer " + accessToken).contentType(ContentType.JSON)
            .body("{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"Nueva1234\"}")
            .when().post("/auth/change-password");
    }
}
