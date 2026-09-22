package com.scaramutti.tms.shared.exception;

import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de como se averigua que restriccion se violo.
 *
 * <p>EL CASO QUE IMPORTA es el del servidor hablando otro idioma. Hibernate saca el nombre
 * parseando el mensaje contra plantillas en ingles, asi que con otro idioma ese nombre llega
 * NULO y solo el campo del protocolo lo tiene. Ese escenario se arma aca a mano porque ninguna
 * base alcanzable lo produce: desarrollo y staging estan en ingles.
 *
 * <p>Sin este caso, borrar la lectura del protocolo entera deja toda la suite en verde. Eso es
 * exactamente lo que pasaba antes de escribirlo.
 */
class ConstraintViolationsTest {

    /** El separador de campos del protocolo. */
    private static final char FIN = '\u0000';

    /**
     * Un error del servidor como llega por el cable: cada campo con su letra y cerrado por el
     * byte cero. {@code C} es el estado SQL, {@code M} el mensaje y {@code n} el nombre de la
     * restriccion, que viaja en su propio campo y por eso NO se traduce.
     */
    private PSQLException serverError(String message, String constraintName) {
        StringBuilder wire = new StringBuilder()
            .append('S').append("ERROR").append(FIN)
            .append('C').append("23505").append(FIN)
            .append('M').append(message).append(FIN);
        if (constraintName != null) {
            wire.append('n').append(constraintName).append(FIN);
        }
        return new PSQLException(new ServerErrorMessage(wire.toString()));
    }

    private ConstraintViolationException violation(SQLException cause, String hibernateName) {
        return new ConstraintViolationException("duplicate key", cause, hibernateName);
    }

    /**
     * El servidor habla otro idioma: Hibernate no reconoce su plantilla y deja el nombre en
     * nulo, pero el campo del protocolo lo trae igual.
     */
    @Test
    void nameOf_whenHibernateCannotParseTheLocalizedMessage_readsItFromTheProtocol() {
        var cve = violation(
            serverError("llave duplicada viola restriccion de unicidad workers_document_number_key",
                "workers_document_number_key"),
            null);

        assertNull(cve.getConstraintName(), "el supuesto del caso: Hibernate no pudo sacarlo del texto");
        assertEquals("workers_document_number_key", ConstraintViolations.nameOf(cve));
    }

    /** Con el servidor en ingles los dos caminos coinciden, que es por lo que nadie lo notaba. */
    @Test
    void nameOf_whenBothAgree_returnsTheSameName() {
        var cve = violation(
            serverError("duplicate key value violates unique constraint", "drivers_license_number_key"),
            "drivers_license_number_key");

        assertEquals("drivers_license_number_key", ConstraintViolations.nameOf(cve));
    }

    /**
     * Con los dos nombres presentes y DISTINTOS gana el del protocolo.
     *
     * <p>Es la razon de ser de esta clase y hasta este caso no la medía nada: los otros casos
     * tienen el mismo nombre de los dos lados o solo uno, asi que invertir el orden ("leer
     * primero el de Hibernate, que es mas barato") los dejaba a todos en verde y borraba la
     * preferencia entera sin que nadie se enterara.
     */
    @Test
    void nameOf_whenBothDisagree_prefersTheProtocol() {
        var cve = violation(serverError("duplicate key", "el_del_protocolo"), "el_de_hibernate");

        assertEquals("el_del_protocolo", ConstraintViolations.nameOf(cve));
    }

    /** Si el protocolo no trae el nombre, se cae al de Hibernate en vez de perderlo. */
    @Test
    void nameOf_whenTheProtocolCarriesNoName_fallsBackToHibernate() {
        var cve = violation(serverError("duplicate key", null), "workers_document_number_key");

        assertEquals("workers_document_number_key", ConstraintViolations.nameOf(cve));
    }

    /** La causa puede no ser del driver: no se revienta, se usa lo que haya. */
    @Test
    void nameOf_whenTheCauseIsNotFromTheDriver_fallsBackToHibernate() {
        var cve = violation(new SQLException("23505"), "workers_document_number_key");

        assertEquals("workers_document_number_key", ConstraintViolations.nameOf(cve));
    }

    /**
     * Sin nombre por ningun lado devuelve nulo, que es lo que hace que quien llama propague en
     * vez de traducir a cualquier cosa.
     */
    @Test
    void nameOf_withoutAnyName_returnsNull() {
        assertNull(ConstraintViolations.nameOf(violation(serverError("boom", null), null)));
    }

    /** La excepcion del driver puede venir encadenada y no ser la primera. */
    @Test
    void nameOf_walksTheChainOfCauses() {
        SQLException first = new SQLException("no es del driver");
        first.setNextException(serverError("duplicate key", "drivers_license_number_key"));

        assertEquals("drivers_license_number_key", ConstraintViolations.nameOf(violation(first, null)));
    }

    /** Hibernate a veces la envuelve y a veces no; las dos formas tienen que resolverse. */
    @Test
    void of_findsTheViolation_wrappedOrNot() {
        var cve = violation(new SQLException("23505"), "x_key");

        assertTrue(ConstraintViolations.of(cve).isPresent(), "sin envolver");
        assertTrue(ConstraintViolations.of(new PersistenceException(cve)).isPresent(), "envuelta");
        assertTrue(ConstraintViolations.of(new PersistenceException("otra cosa")).isEmpty());
    }
}
