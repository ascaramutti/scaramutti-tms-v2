package com.scaramutti.tms.workers.service;

import com.scaramutti.tms.shared.exception.ApiException;
import com.scaramutti.tms.support.WarehouseTestData;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Que la fila se tome con bloqueo AL LEERLA.
 *
 * <p>Existe porque por el endpoint eso NO se puede distinguir, y descubrirlo costó una mutación
 * sobreviviente: si la lectura no bloqueara, la escritura del final chocaría igual contra quien
 * sostiene la fila y, con el tope puesto, se rendiría con el MISMO conflicto. El caso de punta a
 * punta pasa por los dos caminos.
 *
 * <p>Acá se llama solo a la lectura, sin tocar ninguna entidad y sin que haya nada que escribir:
 * lo unico que puede esperar es el bloqueo de esa consulta. Si alguien lo saca, este caso es el
 * unico que se entera.
 */
@QuarkusTest
class WorkerRowLockIntegrationTest {

    /**
     * El tamaño del pool, LEIDO de la configuracion y no copiado: el bucle de mas abajo pide
     * conexiones hasta dar con la que corrio la transaccion, y un numero copiado a mano se
     * desincroniza en silencio. Si el pool crece, el bucle se quedaria corto y el caso fallaria
     * por una razon que no es la suya; si se achica, pediria mas de las que hay.
     */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.jdbc.max-size")
    int maxPoolSize;

    @Inject WorkerRowLock workerRowLock;
    @Inject WarehouseTestData fixtures;
    @Inject DataSource dataSource;
    @Inject jakarta.persistence.EntityManager entityManager;
    @Inject com.scaramutti.tms.shared.repository.WorkerRepository workerRepository;

    @BeforeEach
    @AfterEach
    void cleanupFixtures() {
        fixtures.deleteTestWorkerDependents();
        QuarkusTransaction.requiringNew().run(() -> fixtures.deleteTestWorkers());
    }

    @Test
    void findByIdForUpdate_whenAnotherTransactionHoldsTheRow_translatesTheConflict() throws Exception {
        int id = fixtures.seedWorker("ZTESTK001", "Ana", "Silva", "operator", true);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                "SELECT id FROM public.workers WHERE id = ? FOR NO KEY UPDATE")) {
                lock.setInt(1, id);
                lock.executeQuery().close();
            }

            // SOLO la lectura, adentro de una transaccion y sin tocar nada: no hay ninguna
            // escritura pendiente que pueda chocar en su lugar.
            ApiException thrown = assertThrows(ApiException.class,
                () -> QuarkusTransaction.requiringNew().call(
                    () -> workerRowLock.findByIdForUpdate(id)));

            assertEquals("WRK-013", thrown.code());
            assertEquals(409, thrown.status());
            holder.rollback();
        }
    }

    /** Sin nadie sosteniendo la fila, la misma lectura devuelve el trabajador. */
    @Test
    void findByIdForUpdate_withoutContention_returnsTheWorker() {
        int id = fixtures.seedWorker("ZTESTK002", "Ana", "Silva", "operator", true);

        var worker = QuarkusTransaction.requiringNew().call(() -> workerRowLock.findByIdForUpdate(id));

        assertEquals(id, worker.id);
    }

    /** Un id que no existe es el 404 del modulo, no un error del servidor. */
    @Test
    void findByIdForUpdate_whenTheWorkerDoesNotExist_throwsWRK001() {
        ApiException thrown = assertThrows(ApiException.class,
            () -> QuarkusTransaction.requiringNew().call(() -> workerRowLock.findByIdForUpdate(999999)));

        assertEquals("WRK-001", thrown.code());
    }

    /**
     * EL CASO QUE MIDE LA RAZON DE SER DE LA CLASE: tener la fila tomada NO impide insertar una
     * fila hija.
     *
     * <p>Es el unico que distingue el bloqueo debil del FUERTE. Con el fuerte, cualquier insercion
     * que referencie a la persona se bloquearia mientras se la edita, sobre el pool compartido con
     * los otros modulos: el caso que lo motiva es registrar un retiro de almacen recibido por ella
     * ({@code almacen.withdrawals.received_by}). La hija que se inserta aca es del propio modulo
     * porque el mecanismo es el mismo para TODA hija con clave foranea —el INSERT toma
     * {@code FOR KEY SHARE} sobre el padre, y eso choca con el lock fuerte y no con el debil— y
     * asi el caso no arrastra el armado de otro esquema.
     *
     * <p>Fija el lock por ARRIBA: mata el fuerte. Quien lo fija por abajo es
     * {@link #findByIdForUpdate_twoHoldersOfTheSameRow_doNotBothSucceed()}; hacen falta los dos,
     * porque cada uno solo puede ver una de las dos direcciones en que la fuerza puede estar mal.
     */
    @Test
    void findByIdForUpdate_doesNotBlockInsertingAChildRow() throws Exception {
        int id = fixtures.seedWorker("ZTESTK010", "Ana", "Silva", "operator", true);
        var tomado = new java.util.concurrent.CountDownLatch(1);
        var seguir = new java.util.concurrent.CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            // El bloqueo se toma en OTRO hilo, con su propia transaccion, y se sostiene.
            executor.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                workerRowLock.findByIdForUpdate(id);
                tomado.countDown();
                try {
                    seguir.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(tomado.await(20, TimeUnit.SECONDS), "el bloqueo tiene que tomarse");

            // Y aca, fuera de toda transaccion de la aplicacion, se inserta una fila HIJA que lo
            // referencia. Con el bloqueo fuerte esto esperaria hasta agotar su tope de dos
            // segundos; con el debil pasa de largo.
            long tardo;
            try (Connection otra = dataSource.getConnection()) {
                otra.setAutoCommit(false);
                try (PreparedStatement st = otra.prepareStatement("SET LOCAL lock_timeout = '2s'")) {
                    st.execute();
                }
                try (PreparedStatement st = otra.prepareStatement(
                    "INSERT INTO public.worker_audit_logs (worker_id, change_type, changed_by, "
                        + "logged_at) VALUES (?, 'FIELD_EDIT', ?, now())")) {
                    st.setInt(1, id);
                    st.setInt(2, fixtures.adminId());
                    // El cronometro arranca PEGADO a la sentencia que puede esperar. Pedir la
                    // conexion al pool tolera cinco segundos y el umbral de aca es 1,5: medir las
                    // dos cosas juntas hace fallar el caso bajo carga por algo que no mide.
                    long comienzo = System.nanoTime();
                    st.executeUpdate();
                    tardo = (System.nanoTime() - comienzo) / 1_000_000;
                }
                otra.rollback();
            }
            assertTrue(tardo < 1_500, "insertar la hija no tiene que esperar al bloqueo; tardo " + tardo + " ms");
        } finally {
            seguir.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS), "quedo el bloqueo tomado");
        }
    }

    /**
     * DOS EDITORES DE LA MISMA FILA NO PUEDEN AVANZAR LOS DOS. Es la propiedad por la que existe
     * la clase, y es la unica direccion que ningun otro caso ve.
     *
     * <p>Las dos puntas pasan por el COMPONENTE, y eso es lo que lo hace distinto del caso de mas
     * arriba, donde el testigo toma el lock a mano con una consulta escrita en el test. Un testigo
     * escrito a mano fija la fuerza contra un literal del test y no contra el codigo: debilitar el
     * lock a uno compartido sigue chocando contra ese testigo —porque el literal del test no
     * cambio— y el caso sigue verde mientras la serializacion real ya no existe. Con las dos
     * puntas usando el componente, debilitarlo hace que los dos lados avancen y el caso cae.
     *
     * <p>Entre este y el de la fila hija, la fuerza queda fijada por los dos lados: el otro mata
     * el lock demasiado fuerte, este mata el demasiado debil y el no tomar ninguno.
     */
    @Test
    void findByIdForUpdate_twoHoldersOfTheSameRow_doNotBothSucceed() throws Exception {
        int id = fixtures.seedWorker("ZTESTK020", "Ana", "Silva", "operator", true);
        var tomado = new java.util.concurrent.CountDownLatch(1);
        var seguir = new java.util.concurrent.CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            executor.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                workerRowLock.findByIdForUpdate(id);
                tomado.countDown();
                try {
                    seguir.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(tomado.await(20, TimeUnit.SECONDS), "el primero tiene que tomar la fila");

            ApiException thrown = assertThrows(ApiException.class,
                () -> QuarkusTransaction.requiringNew().call(() -> workerRowLock.findByIdForUpdate(id)),
                "el segundo editor no puede tomar la misma fila mientras el primero la sostiene");
            assertEquals("WRK-013", thrown.code());
            assertEquals(409, thrown.status());
        } finally {
            seguir.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS), "quedo el bloqueo tomado");
        }
    }

    /**
     * La fila se BORRA entre la primera lectura y el lock: es el mismo 404 del modulo y no un
     * error del servidor.
     *
     * <p>Sin el bloque que lo traduce, el lock sobre una fila que ya no esta sale como 500. La
     * carrera se fuerza cargando la entidad en el contexto ANTES de borrarla, para que la primera
     * lectura del componente la encuentre en memoria y sea el lock el que no encuentre nada.
     */
    @Test
    void findByIdForUpdate_whenTheRowVanishesBeforeTheLock_isNotFound() {
        int id = fixtures.seedWorker("ZTESTK021", "Ana", "Silva", "operator", true);

        ApiException thrown = assertThrows(ApiException.class,
            () -> QuarkusTransaction.requiringNew().run(() -> {
                workerRepository.findById(id);
                QuarkusTransaction.requiringNew().run(
                    () -> workerRepository.delete("id = ?1", id));
                workerRowLock.findByIdForUpdate(id);
            }));
        assertEquals("WRK-001", thrown.code());
        assertEquals(404, thrown.status());
    }

    /**
     * El tope se aplica CON SU UNIDAD. Escribir el numero sin unidad no es inocuo: PostgreSQL lo
     * leeria como milisegundos igual, pero el dia que la propiedad vuelva a expresarse en segundos
     * la misma sentencia pasaria a significar otra cosa sin que nada falle.
     */
    @Test
    void findByIdForUpdate_appliesTheConfiguredTimeoutWithItsUnit() {
        int id = fixtures.seedWorker("ZTESTK011", "Ana", "Silva", "operator", true);

        String aplicado = QuarkusTransaction.requiringNew().call(() -> {
            workerRowLock.findByIdForUpdate(id);
            return (String) entityManager.createNativeQuery("SHOW lock_timeout").getSingleResult();
        });

        assertEquals("700ms", aplicado, "el tope se configura en milisegundos, con su unidad");
    }

    /**
     * El tope NO se queda pegado a la conexion cuando la transaccion termina.
     *
     * <p>La conexion vuelve al pool COMPARTIDO con los otros modulos: un tope filtrado los haria
     * rendirse antes de tiempo en operaciones que nada tienen que ver con esta.
     *
     * <p>SE MIDE SOBRE LA CONEXION QUE CORRIO LA TRANSACCION, identificada por el proceso que la
     * atiende del lado del motor. Pedir una conexion cualquiera al pool no sirve: con dieciseis
     * disponibles, lo mas probable es que toque otra, y entonces el caso afirma que no hay fuga
     * sobre una conexion que nunca vio la sentencia. Asi, quitarle {@code LOCAL} a la sentencia
     * hace caer este caso siempre y no una de cada dieciseis veces.
     */
    @Test
    void findByIdForUpdate_doesNotLeakTheTimeoutIntoThePool() throws Exception {
        int id = fixtures.seedWorker("ZTESTK012", "Ana", "Silva", "operator", true);

        int pidDeLaTransaccion = QuarkusTransaction.requiringNew().call(() -> {
            workerRowLock.findByIdForUpdate(id);
            return ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()")
                .getSingleResult()).intValue();
        });

        List<Connection> retenidas = new ArrayList<>();
        try {
            String topeDeEsaConexion = null;
            // Se piden conexiones y se RETIENEN, para no recibir siempre la misma, hasta dar con
            // la que atendio la transaccion. El pool no entrega mas que su tamaño maximo.
            for (int intento = 0; intento < maxPoolSize && topeDeEsaConexion == null; intento++) {
                Connection prestada = dataSource.getConnection();
                retenidas.add(prestada);
                try (PreparedStatement st = prestada.prepareStatement(
                         "SELECT pg_backend_pid(), current_setting('lock_timeout')");
                     ResultSet rs = st.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) == pidDeLaTransaccion) {
                        topeDeEsaConexion = rs.getString(2);
                    }
                }
            }
            assertEquals("0", topeDeEsaConexion,
                "el tope murio con su transaccion en la conexion que la corrio");
        } finally {
            for (Connection prestada : retenidas) {
                prestada.close();
            }
        }
    }

    /**
     * Llamar con cambios pendientes REVIENTA en vez de descartarlos en silencio.
     *
     * <p>Tomar el bloqueo relee la fila, y releer descarta lo que se le haya cambiado en memoria.
     * Quien mute primero y bloquee despues perderia su cambio sin ningun error, y dejaria un
     * rastro afirmando algo que en la fila no esta. Esta guarda es la mitad verificable de esa
     * regla, y sin este caso borrarla no rompe nada.
     */
    @Test
    void findByIdForUpdate_withPendingChanges_failsLoudlyInsteadOfDiscardingThem() {
        int id = fixtures.seedWorker("ZTESTK013", "Ana", "Silva", "operator", true);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> QuarkusTransaction.requiringNew().run(() -> {
                var worker = workerRepository.findById(id);
                worker.firstName = "Cambiado antes de bloquear";
                workerRowLock.findByIdForUpdate(id);
            }));

        assertTrue(thrown.getMessage().contains("ANTES de modificar"),
            "el mensaje tiene que decir que hacer: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Worker"),
            "y que entidades habia cargadas, para poder diagnosticarlo del registro");
    }

    /**
     * Si la fila ya estaba cargada en la transaccion, se RELEE al bloquearla.
     *
     * <p>Un bloqueo a secas la toma pero devuelve la instancia cacheada, con las columnas viejas,
     * y entonces todo lo que decide despues lo hace sobre una foto anterior al bloqueo.
     */
    @Test
    void findByIdForUpdate_refreshesAnEntityAlreadyInThePersistenceContext() {
        int id = fixtures.seedWorker("ZTESTK014", "Ana", "Silva", "operator", true);

        String leido = QuarkusTransaction.requiringNew().call(() -> {
            var cargado = workerRepository.findById(id);
            assertEquals("Ana", cargado.firstName);
            // Otra transaccion cambia la fila mientras esta la tiene cargada.
            fixtures.setWorkerFirstName(id, "Despues");
            return workerRowLock.findByIdForUpdate(id).firstName;
        });

        assertEquals("Despues", leido, "el bloqueo tiene que releer, no devolver la copia vieja");
    }

    /** El bloqueo se suelta al terminar la transaccion, no queda retenido. */
    @Test
    void findByIdForUpdate_releasesTheLockWhenTheTransactionEnds() throws Exception {
        int id = fixtures.seedWorker("ZTESTK015", "Ana", "Silva", "operator", true);
        QuarkusTransaction.requiringNew().run(() -> workerRowLock.findByIdForUpdate(id));

        try (Connection otra = dataSource.getConnection()) {
            otra.setAutoCommit(false);
            try (PreparedStatement st = otra.prepareStatement("SET LOCAL lock_timeout = '2s'")) {
                st.execute();
            }
            try (PreparedStatement st = otra.prepareStatement(
                "SELECT id FROM public.workers WHERE id = ? FOR NO KEY UPDATE")) {
                st.setInt(1, id);
                try (ResultSet rs = st.executeQuery()) {
                    assertTrue(rs.next(), "la fila tiene que poder tomarse de nuevo");
                }
            }
            otra.rollback();
        }
    }

}
