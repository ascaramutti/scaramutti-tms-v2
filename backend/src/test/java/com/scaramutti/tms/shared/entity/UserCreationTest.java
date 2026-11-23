package com.scaramutti.tms.shared.entity;

import com.scaramutti.tms.shared.repository.UserRepository;
import com.scaramutti.tms.shared.repository.WorkerRepository;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Una cuenta creada por la entidad, como la crea el sembrador de dev: nace con su marca de cambio en
 * la de su alta, aunque la firmen antes de grabarla, y el sembrador la deja sin autores. Sin el
 * callback la entidad manda nula una columna obligatoria, y en una base vacia el sembrador no llega
 * a crear sus cuentas. Despues, una escritura por la entidad que no firma (el sembrador rehashea al
 * arrancar) no mueve la marca ni los autores.
 */
@QuarkusTest
class UserCreationTest {

    @Inject UserRepository userRepository;
    @Inject WorkerRepository workerRepository;
    @Inject WarehouseTestData fixtures;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    /** El sembrador pone el alta: la marca de cambio es esa y no la del momento de grabar. */
    @Test
    void anAccountCreatedWithACreationMark_likeTheSeeder_keepsItAsItsChangeMark() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        int userId = createAccount("ZTESTU001", "ztestuseralta1", createdAt, null);

        var authorship = fixtures.userAuthorshipOf(userId);
        assertEquals(createdAt.toInstant(), authorship.createdAt().toInstant());
        assertEquals(createdAt.toInstant(), authorship.updatedAt().toInstant(), "nace sin cambios");
        assertNull(authorship.createdBy(), "el sembrador no tiene quien cree sus cuentas");
        assertNull(authorship.updatedBy());
    }

    @Test
    void anAccountCreatedWithoutACreationMark_getsTheSameMarkInBoth() {
        int userId = createAccount("ZTESTU002", "ztestuseralta2", null, null);

        var authorship = fixtures.userAuthorshipOf(userId);
        assertNotNull(authorship.createdAt());
        assertEquals(authorship.createdAt(), authorship.updatedAt());
    }

    /** Con el alta puesta, la firma previa no le deja una marca de cambio posterior a su alta. */
    @Test
    void anAccountSignedBeforeItsCreation_isBornWithTheMarkOfItsCreation() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        int userId = createAccount("ZTESTU003", "ztestuseralta3", createdAt, fixtures.adminId());

        var authorship = fixtures.userAuthorshipOf(userId);
        assertEquals(createdAt.toInstant(), authorship.updatedAt().toInstant(), "nace sin cambios");
        assertEquals(fixtures.adminId(), authorship.updatedBy());
    }

    /** Sin el alta puesta, tampoco una marca anterior a la que pone el callback. */
    @Test
    void anAccountSignedBeforeItsCreation_withoutACreationMark_isBornWithTheMarkOfItsCreation() {
        int userId = createAccount("ZTESTU004", "ztestuseralta4", null, fixtures.adminId());

        var authorship = fixtures.userAuthorshipOf(userId);
        assertNotNull(authorship.createdAt());
        assertEquals(authorship.createdAt(), authorship.updatedAt(), "nace sin cambios");
        assertEquals(fixtures.adminId(), authorship.updatedBy());
    }

    /**
     * Sin {@code @PreUpdate}: lo que no pasa por markChangedBy, como el rehash del sembrador, deja la
     * marca de cambio y los autores como estaban, aunque la escritura llegue a la base.
     */
    @Test
    void aWriteThatDoesNotSign_likeTheSeedersRehash_movesNeitherTheMarkNorTheAuthors() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        int userId = createAccount("ZTESTU005", "ztestuseralta5", createdAt, fixtures.adminId());
        var before = fixtures.userAuthorshipOf(userId);

        QuarkusTransaction.requiringNew().run(() -> {
            User user = userRepository.findById(userId);
            user.passwordHash = "ztest-otro-hash";
            user.isActive = false;
        });

        assertFalse(fixtures.userIsActive(userId), "precondicion: la escritura llego a la base");
        assertEquals(before, fixtures.userAuthorshipOf(userId), "ni la marca ni los autores");
    }

    private int createAccount(String documentNumber, String username, OffsetDateTime createdAt, Integer signedBy) {
        int workerId = fixtures.seedWorker(documentNumber, "Ana", "Alta", "sales", true);
        return QuarkusTransaction.requiringNew().call(() -> {
            User user = new User();
            user.username = username;
            user.passwordHash = "ztest-no-login";
            user.worker = workerRepository.findById(workerId);
            user.isActive = true;
            user.createdAt = createdAt;
            if (signedBy != null) {
                user.markChangedBy(signedBy);
            }
            userRepository.persist(user);
            return user.id;
        });
    }
}
