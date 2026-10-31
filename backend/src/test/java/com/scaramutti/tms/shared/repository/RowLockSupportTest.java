package com.scaramutti.tms.shared.repository;

import com.scaramutti.tms.shared.exception.ApiError;
import com.scaramutti.tms.shared.exception.ApiException;
import jakarta.persistence.PessimisticLockException;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La banda del tope y la traduccion del choque, sin contenedor: son aritmetica sobre dos numeros y
 * una cadena de causas. El codigo del conflicto lo pone quien llama.
 */
class RowLockSupportTest {

    private enum CallerError implements ApiError {
        BUSY;

        @Override public String code()   { return "ZTEST-409"; }
        @Override public int    status() { return 409; }
        @Override public String title()  { return "Busy"; }
        @Override public String detail() { return "La fila la tiene otra operacion"; }
    }

    private static int band(int lockTimeoutMillis, int maxLockWaits, Duration poolWait) {
        return RowLockSupport.requireUsableLockTimeout("app.ztest.lock-timeout-ms", lockTimeoutMillis,
            maxLockWaits, poolWait);
    }

    // ---------- la banda ------------------------------------------------------------

    /** Cero es el tope apagado: el motor espera sin limite. El mensaje nombra la propiedad. */
    @Test
    void requireUsableLockTimeout_rejectsZero() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> band(0, 2, Duration.ofSeconds(5)));
        assertTrue(thrown.getMessage().startsWith("app.ztest.lock-timeout-ms tiene que ser mayor que cero"),
            thrown.getMessage());
    }

    @Test
    void requireUsableLockTimeout_rejectsANegativeValue() {
        assertThrows(IllegalStateException.class, () -> band(-1, 2, Duration.ofSeconds(5)));
    }

    /**
     * Igualar la espera del pool ya invierte el orden de las rendiciones: se rechaza tambien el
     * exacto. Con un techo divisible por las esperas, para medir la igualdad y no un redondeo.
     */
    @Test
    void requireUsableLockTimeout_rejectsABudgetThatExactlyReachesThePoolWait() {
        assertThrows(IllegalStateException.class, () -> band(2500, 2, Duration.ofMillis(5000)));
    }

    /** Y un milisegundo por debajo entra: el limite es el techo, no un margen inventado. */
    @Test
    void requireUsableLockTimeout_acceptsABudgetOneMillisecondBelowThePoolWait() {
        assertEquals(2500, band(2500, 2, Duration.ofMillis(5001)));
    }

    /** El presupuesto multiplica por las esperas: el mismo tope que entra con dos no entra con catorce. */
    @Test
    void requireUsableLockTimeout_multipliesByTheWaitsOfTheCaller() {
        assertEquals(380, band(380, 2, Duration.ofSeconds(5)));
        assertThrows(IllegalStateException.class, () -> band(380, 14, Duration.ofSeconds(5)));
    }

    /** El producto va en long: en int, un tope enorme daria la vuelta a negativo y entraria. */
    @Test
    void requireUsableLockTimeout_doesNotOverflowWithAHugeTimeout() {
        assertThrows(IllegalStateException.class, () -> band(Integer.MAX_VALUE, 2, Duration.ofSeconds(5)));
    }

    /** El techo se compara en milisegundos: truncado a segundos, 4,5s pasaria a 4 y 2 x 2100 no entraria. */
    @Test
    void requireUsableLockTimeout_comparesAgainstThePoolWaitWithoutTruncatingItToSeconds() {
        assertEquals(2100, band(2100, 2, Duration.ofMillis(4500)));
    }

    // ---------- la traduccion del choque ----------------------------------------------

    /** El choque como llega de verdad: envuelto dos veces, con el estado del motor al fondo. */
    private static PersistenceException wrapping(String sqlState) {
        return new PersistenceException("fallo de base",
            new IllegalStateException("envoltorio de Hibernate", new SQLException("mensaje del motor", sqlState)));
    }

    /** La espera agotada, el abrazo mortal y el fallo de serializacion: los tres son "no pude tomarla". */
    @ParameterizedTest
    @ValueSource(strings = {"55P03", "40P01", "40001"})
    void asLockConflictOrRethrow_withALockConflict_translatesToTheCallersConflict(String sqlState) {
        RuntimeException translated = RowLockSupport.asLockConflictOrRethrow(wrapping(sqlState),
            CallerError.BUSY, "la cuenta id=42");

        assertInstanceOf(ApiException.class, translated);
        assertEquals("ZTEST-409", ((ApiException) translated).code());
    }

    /** Se decide por el estado y no por el tipo: el abrazo mortal llega, hoy, como un bloqueo pesimista. */
    @Test
    void asLockConflictOrRethrow_withADeadlockAsAPessimisticLock_translatesToo() {
        PersistenceException misleadingType =
            new PessimisticLockException(new SQLException("deadlock detected", "40P01"));

        RuntimeException translated = RowLockSupport.asLockConflictOrRethrow(misleadingType,
            CallerError.BUSY, "la cuenta id=42");

        assertEquals("ZTEST-409", ((ApiException) translated).code());
    }

    /** Cualquier otra falla se propaga tal cual: traducir todo error a un conflicto reintentable seria peor. */
    @Test
    void asLockConflictOrRethrow_withAnotherFailure_propagatesItUntouched() {
        PersistenceException original = wrapping("23505");

        assertSame(original, RowLockSupport.asLockConflictOrRethrow(original, CallerError.BUSY, "x"));
    }

    @Test
    void asLockConflictOrRethrow_withoutASqlState_propagatesIt() {
        PersistenceException original = new PersistenceException("sin causa de base");

        assertSame(original, RowLockSupport.asLockConflictOrRethrow(original, CallerError.BUSY, "x"));
    }
}
