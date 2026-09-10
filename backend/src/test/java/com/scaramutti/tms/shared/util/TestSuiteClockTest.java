package com.scaramutti.tms.shared.util;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * La suite corre con el reloj de la JVM en UTC, fijado en el pom, para que un calculo que se
 * apoye en la zona del proceso no pase por casualidad en la maquina de quien lo ejecuta. Este
 * caso es el que avisa si alguien retira esa linea del pom.
 *
 * <p>Compara la zona NORMALIZADA y no su nombre: {@code UTC}, {@code Etc/UTC} y {@code Z} son
 * tres textos para el mismo desplazamiento, y atarse al texto haria fallar el caso por como se
 * escribio la propiedad en vez de por el reloj que quedo.
 *
 * <p>Un limite honesto: en una maquina que YA esta en UTC, este caso pasa igual con la linea del
 * pom puesta o quitada, asi que ahi no discrimina. Discrimina en una maquina en otra zona, que es
 * donde el reloj heredado hace dano. Medido el 2026-09-10 forzando {@code TZ=America/Lima}: sin la
 * linea del pom, este caso falla.
 */
class TestSuiteClockTest {

    @Test
    void theSuiteRunsWithTheJvmClockInUtc() {
        assertEquals(ZoneOffset.UTC, ZoneId.systemDefault().normalized(),
            "La suite dejo de correr en UTC: revisar el argLine del bloque de surefire en el pom");
    }
}
