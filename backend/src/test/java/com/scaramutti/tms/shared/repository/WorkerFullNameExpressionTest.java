package com.scaramutti.tms.shared.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fija la expresion con la que se arma el nombre completo de una persona en SQL.
 *
 * <p>La expresion se mudo de un repositorio a otro y paso de constante con alias fijo a
 * metodo con el alias por parametro. Lo unico que no puede cambiar es el texto que produce:
 * cinco consultas de tres modulos lo concatenan, y un espacio de mas o un {@code trim}
 * perdido cambia como se llama la misma persona segun por donde se la mire.
 *
 * <p>El caso compara contra un literal escrito aca y no contra el metodo: derivarlo de la
 * misma fuente dejaria pasar cualquier cambio, porque las dos puntas se moverian juntas.
 */
class WorkerFullNameExpressionTest {

    @Test
    void theExpression_isTheSameOneTheQueriesUsedBeforeTheMove() {
        assertEquals(
            "trim(w.first_name || ' ' || w.last_name)",
            WorkerRepository.fullNameExpression("w"));
    }

    /** El alias es parametro porque el detalle compone dos nombres en la misma sentencia. */
    @Test
    void theExpression_appliesTheAliasItReceives() {
        assertEquals(
            "trim(cw.first_name || ' ' || cw.last_name)",
            WorkerRepository.fullNameExpression("cw"));
    }
}
