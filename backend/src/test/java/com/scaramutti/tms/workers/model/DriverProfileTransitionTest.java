package com.scaramutti.tms.workers.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La matriz ENTERA de que hacer con la ficha de conductor al editar.
 *
 * <p>Dieciocho combinaciones sobre un trabajador activo, mas las del inactivo. Estan todas y no
 * una muestra: cada celda mata su propia comparacion invertida, y el nombre de la fila dice cual.
 * Por HTTP cada una costaria un pedido con su base; aca cuestan lo que tarda una comparacion.
 */
class DriverProfileTransitionTest {

    @ParameterizedTest(name = "fila={0}/{1} cargo={2} ficha={3} -> {4}")
    @CsvSource({
        // hay fila, activa, modalidad, vino ficha, esperado   (trabajador ACTIVO)
        "no,  no,  NONE,     no,  NOTHING",
        "no,  no,  NONE,     si,  REJECT",
        "no,  no,  OPTIONAL, no,  NOTHING",
        "no,  no,  OPTIONAL, si,  CREATE",
        "no,  no,  REQUIRED, no,  REJECT",
        "no,  no,  REQUIRED, si,  CREATE",

        "si,  si,  NONE,     no,  DEACTIVATE",
        "si,  si,  NONE,     si,  REJECT",
        "si,  si,  OPTIONAL, no,  DEACTIVATE",
        "si,  si,  OPTIONAL, si,  UPDATE",
        "si,  si,  REQUIRED, no,  REJECT",
        "si,  si,  REQUIRED, si,  UPDATE",

        "si,  no,  NONE,     no,  STAY_OFF",
        "si,  no,  NONE,     si,  REJECT",
        "si,  no,  OPTIONAL, no,  STAY_OFF",
        "si,  no,  OPTIONAL, si,  REACTIVATE",
        "si,  no,  REQUIRED, no,  REJECT",
        "si,  no,  REQUIRED, si,  REACTIVATE",
    })
    void decide_coversTheWholeMatrix_forAnActiveWorker(String hasRow, String rowIsActive,
            DriverProfileMode mode, String bodyHasProfile, DriverProfileTransition expected) {
        assertEquals(expected, DriverProfileTransition.decide(
            "si".equals(hasRow), "si".equals(rowIsActive), mode, "si".equals(bodyHasProfile), true));
    }

    /**
     * Sobre un trabajador DADO DE BAJA la ficha nunca se enciende, PERO sus datos se escriben
     * igual.
     *
     * <p>Son dos ejes y la matriz los separa. Confundirlos fue un defecto real: mientras "no
     * encender" significo tambien "no escribir", a alguien dado de baja con un cargo que EXIGE
     * ficha no habia ningun cuerpo que le corrigiera la licencia, porque mandarla se descartaba
     * en silencio y no mandarla era un rechazo.
     */
    @ParameterizedTest(name = "inactivo: fila={0}/{1} cargo={2} ficha={3} -> {4}")
    @CsvSource({
        // sin fila y con ficha en el cuerpo, nace APAGADA: el dato se guarda y no se enciende.
        // Antes esto era NOTHING y la licencia se descartaba en silencio con 200; con REQUIRED la
        // API exigia un campo que despues ignoraba.
        "no,  no,  REQUIRED, si,  CREATE_OFF",
        "no,  no,  OPTIONAL, si,  CREATE_OFF",
        // sin fila y sin ficha en el cuerpo no hay nada que guardar
        "no,  no,  OPTIONAL, no,  NOTHING",
        "no,  no,  NONE,     no,  NOTHING",
        // con fila, los datos se escriben y la fila queda apagada
        "si,  no,  REQUIRED, si,  UPDATE_OFF",
        "si,  no,  OPTIONAL, si,  UPDATE_OFF",
        "si,  si,  REQUIRED, si,  UPDATE_OFF",
        "si,  si,  OPTIONAL, si,  UPDATE_OFF",
        // sin ficha en el cuerpo no hay datos que escribir: solo se apaga
        "si,  si,  OPTIONAL, no,  DEACTIVATE",
        "si,  no,  OPTIONAL, no,  STAY_OFF",
        "si,  si,  NONE,     no,  DEACTIVATE",
        "si,  no,  NONE,     no,  STAY_OFF",
    })
    void decide_forAnInactiveWorker_writesTheDataButNeverTurnsItOn(String hasRow, String rowIsActive,
            DriverProfileMode mode, String bodyHasProfile, DriverProfileTransition expected) {
        assertEquals(expected, DriverProfileTransition.decide(
            "si".equals(hasRow), "si".equals(rowIsActive), mode, "si".equals(bodyHasProfile), false));
    }

    /**
     * El invariante, afirmado aparte de la tabla: sobre un dado de baja, NINGUNA combinacion
     * devuelve una transicion que encienda la fila.
     *
     * <p>Esta separado a proposito. La tabla de arriba dice que devuelve cada celda; esto dice
     * que ninguna celda futura puede encenderla, aunque alguien agregue una fila a la tabla.
     *
     * <p>Es una LISTA PERMITIDA y no una lista prohibida: una lista de las que encienden deja
     * pasar cualquier transicion nueva que nadie se acordo de agregarle, y una transicion nueva es
     * justamente como aparecio {@code CREATE_OFF}. Asi, la que se sume tiene que declararse aca.
     */
    @Test
    void decide_forAnInactiveWorker_neverReturnsATransitionThatTurnsItOn() {
        Set<DriverProfileTransition> dejanApagada = Set.of(DriverProfileTransition.REJECT,
            DriverProfileTransition.NOTHING, DriverProfileTransition.CREATE_OFF,
            DriverProfileTransition.UPDATE_OFF, DriverProfileTransition.DEACTIVATE,
            DriverProfileTransition.STAY_OFF);
        for (boolean hasRow : new boolean[] {true, false}) {
            for (boolean rowIsActive : new boolean[] {true, false}) {
                for (DriverProfileMode mode : DriverProfileMode.values()) {
                    for (boolean body : new boolean[] {true, false}) {
                        var decidida = DriverProfileTransition.decide(hasRow, rowIsActive, mode, body, false);
                        assertTrue(dejanApagada.contains(decidida),
                            "sobre un inactivo, " + decidida + " no esta entre las que dejan la ficha apagada");
                    }
                }
            }
        }
    }

    /**
     * Y la otra mitad del eje: sobre un dado de baja, una ficha que viene en el cuerpo y pasa el
     * rechazo SIEMPRE se escribe. Es lo que el defecto de {@code NOTHING} violaba: el dato se
     * descartaba en silencio y la respuesta era 200.
     */
    @Test
    void decide_forAnInactiveWorker_neverDiscardsAProfileThatCameInTheBody() {
        for (boolean hasRow : new boolean[] {true, false}) {
            for (boolean rowIsActive : new boolean[] {true, false}) {
                for (DriverProfileMode mode : DriverProfileMode.values()) {
                    var decidida = DriverProfileTransition.decide(hasRow, rowIsActive, mode, true, false);
                    assertTrue(decidida == DriverProfileTransition.REJECT || decidida.writesBodyData(),
                        "fila=" + hasRow + "/" + rowIsActive + " cargo=" + mode + ": " + decidida
                            + " descarta la ficha del cuerpo");
                }
            }
        }
    }

    /** El rechazo depende SOLO de la modalidad y de si vino la ficha, nunca del estado de la fila. */
    @Test
    void decide_theRejection_doesNotDependOnTheRowNorOnTheWorker() {
        for (boolean hasRow : new boolean[] {true, false}) {
            for (boolean rowIsActive : new boolean[] {true, false}) {
                for (boolean workerIsActive : new boolean[] {true, false}) {
                    assertEquals(DriverProfileTransition.REJECT, DriverProfileTransition.decide(
                        hasRow, rowIsActive, DriverProfileMode.NONE, true, workerIsActive));
                    assertEquals(DriverProfileTransition.REJECT, DriverProfileTransition.decide(
                        hasRow, rowIsActive, DriverProfileMode.REQUIRED, false, workerIsActive));
                }
            }
        }
    }

    /**
     * Las que escriben datos del cuerpo son exactamente esas cinco.
     *
     * <p>Importa mas de lo que parece: de esta lista depende si la licencia del cuerpo se compara
     * contra las demas. Las que NO escriben son justamente las que llegan sin ficha en el cuerpo,
     * asi que una de ellas aca adentro haria que el chequeo lea una ficha que no vino, y CUALQUIER
     * edicion sin ficha fallaria con 500. Y una que escribe y quedara afuera guardaria una licencia
     * sin compararla antes.
     */
    @Test
    void writesBodyData_isTrueOnlyForTheFiveThatTouchTheRowWithTheBody() {
        Set<DriverProfileTransition> escriben = new HashSet<>();
        for (DriverProfileTransition t : DriverProfileTransition.values()) {
            if (t.writesBodyData()) escriben.add(t);
        }
        assertEquals(Set.of(DriverProfileTransition.CREATE, DriverProfileTransition.CREATE_OFF,
            DriverProfileTransition.UPDATE, DriverProfileTransition.REACTIVATE,
            DriverProfileTransition.UPDATE_OFF), escriben);
    }

    /** Toda combinacion resuelve: ninguna cae en un hueco. */
    @Test
    void decide_neverReturnsNull() {
        for (boolean hasRow : new boolean[] {true, false}) {
            for (boolean rowIsActive : new boolean[] {true, false}) {
                for (DriverProfileMode mode : DriverProfileMode.values()) {
                    for (boolean body : new boolean[] {true, false}) {
                        for (boolean worker : new boolean[] {true, false}) {
                            assertTrue(DriverProfileTransition.decide(
                                hasRow, rowIsActive, mode, body, worker) != null);
                        }
                    }
                }
            }
        }
    }
}
