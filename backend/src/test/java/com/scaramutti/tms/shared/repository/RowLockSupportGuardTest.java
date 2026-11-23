package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.entity.ProductCategory;
import com.scaramutti.tms.shared.entity.User;
import com.scaramutti.tms.shared.entity.Worker;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.TransactionalException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Las guardas de tomar una fila antes de leerla, llamadas directo: por el endpoint no se ven,
 * porque el cambio propio no carga nada antes. Cada caso corre en su transaccion y la deshace.
 */
@QuarkusTest
class RowLockSupportGuardTest {

    @Inject RowLockSupport rowLockSupport;
    @Inject EntityManager entityManager;
    @Inject WarehouseTestData fixtures;
    @Inject DataSource dataSource;

    @ConfigProperty(name = "quarkus.datasource.jdbc.max-size")
    int maxPoolSize;

    private int userId;
    private int workerId;

    @BeforeEach
    void seedAccount() {
        cleanupFixtures();
        workerId = fixtures.seedWorker("ZTESTGUARD1", "Guarda", "Fila", "sales", true);
        userId = fixtures.seedUserFor(workerId, "ztestuserguarda1", "sales");
    }

    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    /** Ya cargada, la entidad traeria las columnas de antes de esperar: falla en vez de devolverla. */
    @Test
    void lockThenFind_withTheRowAlreadyLoaded_failsLoudly() {
        inATransactionThatRollsBack(() -> {
            entityManager.find(User.class, userId);

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> rowLockSupport.lockThenFind(User.class, userId, 600));
            assertTrue(thrown.getMessage().contains("ya esta cargada"), thrown.getMessage());
        });
    }

    /** Otra fila cargada no molesta: la guarda mira esa fila de esa entidad, no cualquiera. */
    @Test
    void lockThenFind_withAnotherEntityLoaded_takesAndReadsTheRow() {
        inATransactionThatRollsBack(() -> {
            entityManager.find(Worker.class, workerId);

            Optional<User> found = rowLockSupport.lockThenFind(User.class, userId, 600);

            assertEquals(userId, found.orElseThrow().id);
            assertSame(found.get().worker, entityManager.find(Worker.class, workerId));
        });
    }

    /** Las dos mitades de la guarda: otra cuenta cargada no frena, ni una entidad de otro tipo con el mismo id. */
    @Test
    void lockThenFind_withAnotherAccountOrTheSameIdOfAnotherEntityLoaded_takesTheRow() {
        int adminId = fixtures.adminId();
        inATransactionThatRollsBack(() -> {
            entityManager.find(User.class, adminId);
            assertEquals(userId, rowLockSupport.lockThenFind(User.class, userId, 600).orElseThrow().id);
        });
        Integer sharedId = sharedIdOfAUserAndAWorker();
        assertTrue(sharedId != null, "precondicion: una cuenta y un trabajador con el mismo id (el sembrador los da)");
        inATransactionThatRollsBack(() -> {
            entityManager.find(Worker.class, sharedId);
            assertEquals(sharedId, rowLockSupport.lockThenFind(User.class, sharedId, 600).orElseThrow().id);
        });
    }

    /**
     * El tope muere con su transaccion: la conexion vuelve al pool compartido, y un tope pegado haria
     * rendirse antes de tiempo a otros modulos. Se mide sobre la conexion que corrio la transaccion,
     * reteniendo las del pool hasta dar con su proceso: otra cualquiera no vio la sentencia.
     */
    @Test
    void lockThenFind_doesNotLeakTheTimeoutIntoThePool() throws Exception {
        int pidOfTheTransaction = QuarkusTransaction.requiringNew().call(() -> {
            rowLockSupport.lockThenFind(User.class, userId, 437);
            return ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
        });

        List<Connection> held = new ArrayList<>();
        try {
            String timeoutOfThatConnection = null;
            for (int attempt = 0; attempt < maxPoolSize && timeoutOfThatConnection == null; attempt++) {
                Connection borrowed = dataSource.getConnection();
                held.add(borrowed);
                try (PreparedStatement query = borrowed.prepareStatement(
                         "SELECT pg_backend_pid(), current_setting('lock_timeout')");
                     ResultSet rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) == pidOfTheTransaction) {
                        timeoutOfThatConnection = rows.getString(2);
                    }
                }
            }
            assertEquals("0", timeoutOfThatConnection, "el tope murio con su transaccion en la conexion que la corrio");
        } finally {
            for (Connection borrowed : held) {
                borrowed.close();
            }
        }
    }

    /** Un cambio pendiente lo volcaria la sentencia del lock antes de tomarlo. */
    @Test
    void lockThenFind_withAChangePending_failsLoudly() {
        inATransactionThatRollsBack(() -> {
            entityManager.find(Worker.class, workerId).firstName = "Pendiente";

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> rowLockSupport.lockThenFind(User.class, userId, 600));
            assertTrue(thrown.getMessage().contains("cambios pendientes"), thrown.getMessage());
        });
    }

    @Test
    void lockThenFind_withAMissingRow_returnsEmpty() {
        inATransactionThatRollsBack(() -> {
            assertTrue(rowLockSupport.lockThenFind(User.class, 999_999, 600).isEmpty());
        });
    }

    /**
     * La tabla sale del mapeo de la entidad, con su esquema: una de {@code almacen} se toma ahi, y una
     * clase sin {@code @Table} se rechaza en vez de armar la sentencia con otra tabla.
     */
    @Test
    void lockThenFind_takesTheTableOfTheEntity_withItsSchema() {
        inATransactionThatRollsBack(() -> {
            assertTrue(rowLockSupport.lockThenFind(ProductCategory.class, 999_999, 600).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> rowLockSupport.lockThenFind(String.class, 1, 600));
        });
    }

    /** El tope queda puesto para toda la transaccion, con el valor del llamador. */
    @Test
    void lockThenFind_setsTheTimeoutOfTheTransaction() {
        inATransactionThatRollsBack(() -> {
            rowLockSupport.lockThenFind(User.class, userId, 437);

            assertEquals("437ms", entityManager.createNativeQuery("SHOW lock_timeout").getSingleResult());
        });
    }

    /** Un id que es a la vez de una cuenta y de un trabajador, o null si no hay. */
    private Integer sharedIdOfAUserAndAWorker() {
        List<?> ids = QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery(
                "SELECT u.id FROM public.users u JOIN public.workers w ON w.id = u.id ORDER BY u.id LIMIT 1")
            .getResultList());
        return ids.isEmpty() ? null : ((Number) ids.get(0)).intValue();
    }

    /**
     * Se deshace al final y no se marca antes: marcada, Hibernate da la transaccion por inactiva y
     * la sentencia del tope falla por eso, no por la guarda.
     */
    private static void inATransactionThatRollsBack(Runnable block) {
        QuarkusTransaction.begin();
        try {
            block.run();
        } finally {
            QuarkusTransaction.rollback();
        }
    }

    /** Un tope en cero seria la espera sin limite: la pieza lo rechaza aunque quien llame no tenga banda. */
    @Test
    void lockThenFind_withAZeroTimeout_isRejected() {
        inATransactionThatRollsBack(() -> assertThrows(IllegalArgumentException.class,
            () -> rowLockSupport.lockThenFind(User.class, userId, 0)));
    }

    /** Fuera de una transaccion el tope y el lock no durarian nada: se rechaza la llamada de entrada. */
    @Test
    void lockThenFind_outsideATransaction_isRejected() {
        assertThrows(TransactionalException.class,
            () -> rowLockSupport.lockThenFind(User.class, userId, 600));
    }
}
