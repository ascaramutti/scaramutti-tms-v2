package com.scaramutti.tms.shared.exception;

import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;

import java.sql.SQLException;
import java.util.Optional;

/**
 * De que restriccion se quejo la base, para traducir un choque a un codigo de negocio.
 *
 * <p>Vive aca y no adentro de un servicio porque trabajadores es el NOVENO SERVICIO que
 * necesita esto (repartidos en cuatro modulos) y los ocho anteriores tienen su propia copia. Todas leen el nombre de la
 * restriccion de Hibernate; esta lo lee del PROTOCOLO, que es lo unico que no depende del
 * idioma del servidor. Nace compartida para que el proximo copie la que anda.
 *
 * <p>Por que importa el idioma: Hibernate saca el nombre parseando el mensaje del servidor
 * contra plantillas en ingles ("violates unique constraint"). Ese mensaje es traducible, asi
 * que con un servidor en otro idioma el nombre llega nulo, la traduccion no reconoce nada y
 * una carrera que el contrato promete como conflicto sale como error interno sin cuerpo. Las
 * bases de desarrollo y de staging estan en ingles, asi que ningun test ni ningun humo puede
 * delatarlo: por eso se lee del campo del protocolo, que no se traduce nunca.
 *
 * <p>Este es el unico lugar del codigo de produccion que menciona el driver. Esa es la razon
 * de que la clase exista: que el tipo del driver no aparezca en nueve servicios.
 */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /** La violacion adentro de la excepcion de persistencia, si la hay. */
    public static Optional<ConstraintViolationException> of(PersistenceException ex) {
        if (ex instanceof ConstraintViolationException cve) {
            return Optional.of(cve);
        }
        Throwable cause = ex.getCause();
        return (cause instanceof ConstraintViolationException cve) ? Optional.of(cve) : Optional.empty();
    }

    /**
     * El nombre de la restriccion violada, preferido del protocolo y con el de Hibernate como
     * respaldo. Puede ser nulo: una violacion sin nombre no cae en ninguna rama conocida y
     * quien llama tiene que propagarla en vez de traducirla a cualquier cosa.
     */
    public static String nameOf(ConstraintViolationException cve) {
        SQLException sqlException = cve.getSQLException();
        while (sqlException != null) {
            if (sqlException instanceof PSQLException psqlException
                && psqlException.getServerErrorMessage() != null
                && psqlException.getServerErrorMessage().getConstraint() != null) {
                return psqlException.getServerErrorMessage().getConstraint();
            }
            sqlException = sqlException.getNextException();
        }
        return cve.getConstraintName();
    }
}
