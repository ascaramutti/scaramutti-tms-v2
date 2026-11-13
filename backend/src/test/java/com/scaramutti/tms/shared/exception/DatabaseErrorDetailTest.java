package com.scaramutti.tms.shared.exception;

import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.transaction.UserTransaction;
import javax.sql.DataSource;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.scaramutti.tms.support.LockWaiters.UNTIL_IT_ARRIVES_MILLIS;
import static com.scaramutti.tms.support.LockWaiters.awaitWaiters;
import static com.scaramutti.tms.support.LockWaiters.backendPid;
import static com.scaramutti.tms.support.LogCapture.logsWhile;
import static com.scaramutti.tms.support.LogCapture.printed;
import static com.scaramutti.tms.support.TestAuth.adminToken;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DatabaseErrorDetailTest {

    private static final String PROPERTY = "quarkus.datasource.jdbc.additional-jdbc-properties.logServerErrorDetail";
    private static final String HASH_MARK = "ztest-hash-marca";

    @Inject WarehouseTestData fixtures;
    @Inject EntityManager entityManager;
    @Inject UserTransaction transaction;
    @Inject DataSource dataSource;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
        fixtures.deleteTestDocumentTypes();
    }

    /** La carrera del documento: con el chequeo previo, un documento ya confirmado no llega al indice. */
    @Test
    void aTranslatedDuplicate_leavesNeitherTheDetailNorTheValueInTheLog() throws Exception {
        int documentTypeId = fixtures.seedDocumentType("ZTDOC97", "ZTEST generico", 20, null, true);
        String token = adminToken();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<String> logs;

        try (Connection uncommitted = dataSource.getConnection()) {
            uncommitted.setAutoCommit(false);
            try (PreparedStatement insert = uncommitted.prepareStatement(
                "INSERT INTO public.workers (first_name, paternal_last_name, document_type_id, "
                    + "document_number, role_id, hire_date, is_active, created_at, updated_at) "
                    + "VALUES ('Zz', 'Carrera', ?, 'ZTESTDET01', ?, DATE '2024-03-01', true, now(), now())")) {
                insert.setInt(1, documentTypeId);
                insert.setInt(2, fixtures.roleIdOf("operator"));
                insert.executeUpdate();
            }

            logs = logsWhile(() -> {
                try {
                    Future<String> code = executor.submit(() -> given()
                        .header("Authorization", "Bearer " + token).contentType("application/json")
                        .body("""
                            {"firstName":"Juan","paternalLastName":"Pérez","documentTypeId":%d,
                             "documentNumber":"ZTESTDET01","role":"operator","hireDate":"2024-03-01"}"""
                            .formatted(documentTypeId))
                        .when().post("/workers")
                        .then().statusCode(409).extract().<String>path("code"));
                    try (Connection observer = dataSource.getConnection()) {
                        assertTrue(awaitWaiters(observer, backendPid(uncommitted), 1, code, UNTIL_IT_ARRIVES_MILLIS),
                            "precondicion: el alta espera el indice del documento");
                    }
                    uncommitted.commit();
                    assertEquals("WRK-002", code.get(20, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertTrue(logs.stream().anyMatch(line -> line.contains("SQLState: 23505")),
            "la captura escucha el choque: " + logs);
        assertTrue(logs.stream().noneMatch(line -> line.contains("Detail:") || line.contains("ZTESTDET01")),
            "el detalle o el documento en el log: " + logs);
    }

    @Test
    void aPropagatedViolation_leavesNeitherTheFailingRowNorTheHash() {
        PersistenceException[] propagated = new PersistenceException[1];

        // La marca en lugar del hash real: el motor escribe la fila rechazada en su propio log.
        List<String> logs = logsWhile(() -> propagated[0] = violationOf(
            "UPDATE public.users SET password_hash = ?1, username = 'X' || left(username, 49)"
                + " WHERE id = (SELECT min(id) FROM public.users)", HASH_MARK));

        // La cadena impresa es lo que escribe quien la registra al propagarla.
        String chain = printed(propagated[0]);
        assertTrue(chain.contains("chk_users_username_lowercase"), chain);
        assertFalse(chain.contains("Failing row") || chain.contains(HASH_MARK), chain);
        assertTrue(logs.stream().anyMatch(line -> line.contains("23514")), "la captura escucha el choque: " + logs);
        assertTrue(logs.stream().noneMatch(line -> line.contains("Failing row") || line.contains(HASH_MARK)),
            "la fila o el hash en el log: " + logs);
    }

    @Test
    void aDuplicate_keepsItsConstraintName_forHibernate() {
        fixtures.seedWorker("ZTESTDET02");
        fixtures.seedWorker("ZTESTDET03");

        PersistenceException propagated = violationOf(
            "UPDATE public.workers SET document_number = ?1 WHERE document_number = ?2", "ZTESTDET02", "ZTESTDET03");

        ConstraintViolationException violation = ConstraintViolations.of(propagated).orElseThrow();
        assertEquals("workers_document_number_key", violation.getConstraintName());
        assertFalse(printed(propagated).contains("ZTESTDET02"), printed(propagated));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev", "test"})
    void theDetailPropertyIsOff_inEveryProfile(String profile) throws IOException {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
            .addDefaultInterceptors()
            .withProfile(profile)
            .withSources(new PropertiesConfigSource(
                Thread.currentThread().getContextClassLoader().getResource("application.properties")))
            .build();

        assertEquals(Optional.of("false"), config.getOptionalValue(PROPERTY, String.class));
    }

    // ---------- fixtures ----------------------------------------------------------

    /** Los valores van como parametros, como en los servicios: Hibernate copia la sentencia en su mensaje. */
    private PersistenceException violationOf(String sql, Object... parameters) {
        try {
            transaction.begin();
            try {
                Query query = entityManager.createNativeQuery(sql);
                for (int i = 0; i < parameters.length; i++) {
                    query.setParameter(i + 1, parameters[i]);
                }
                return assertThrows(PersistenceException.class, query::executeUpdate);
            } finally {
                transaction.rollback();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
