package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.operations.dto.embedded.ServiceWeekCycleSummary;
import com.scaramutti.tms.shared.exception.CommonError;
import com.scaramutti.tms.shared.util.DateUtils;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * El ciclo operativo de la empresa (RN-OP14): de MIERCOLES a MARTES, en hora de Lima.
 *
 * <p>El calculo ({@link #at}) es una funcion PURA —{@link #current} solo le pasa el reloj— y vive
 * aparte del service por dos motivos. El primero es que asi se puede
 * probar con instantes literales: desde un test de integracion, todo borde de dia de la semana
 * depende del dia en que corra la suite —un caso "martes 23:00" solo discrimina un corte
 * lunes→domingo si hoy cae en ciertos dias—, con lo cual la regla mas delicada del modulo quedaria
 * cubierta a medias.
 *
 * <p>El segundo motivo vale para la CLASE, no para {@link #at}: hay dos consumidores y entran por
 * puertas distintas. El strip de indicadores entra por {@link #at} (instante a ciclo, para saber en
 * que semana cae ahora). El reporte semanal entra por {@link #startingOn} (el miercoles PEDIDO a
 * ciclo, sin fabricar un instante) y de la clase saca ademas los dos bordes de su consulta y su
 * indicador de cerrada. <b>Ningun test del reporte ejercita {@code at}</b>: romperlo lo ven los
 * casos del strip y los de esta clase, no los del reporte.
 *
 * <p><b>Por que se escribe con tanto cuidado.</b> El sistema anterior calcula este ciclo en DOS
 * lugares y uno de los dos esta mal. El del reporte arma instantes absolutos y da bien; el del
 * tablero arma medianoches del reloj DEL PROCESO, asi que con el servidor en UTC —que es como corre
 * hoy— la ventana se desplaza cinco horas: las ultimas cinco horas del martes se van a la semana
 * siguiente y las del martes anterior se cuelan hacia adentro. Un viaje cerrado el martes a las
 * 23:00 de Lima cae en la semana equivocada. Aca se copia la semantica del reporte, que es la
 * correcta, y no la del tablero, que es el que este endpoint reemplaza.
 *
 * <p><b>La ventana es SEMIABIERTA</b>, {@code [miercoles 00:00, miercoles siguiente 00:00)}, y no
 * cerrada en "martes 23:59:59.999" como la escribe el sistema anterior. La columna guarda
 * MICROsegundos, asi que un tope en milisegundos deja fuera los ultimos 999 microsegundos del
 * martes. El semiabierto no tiene borde perdido.
 *
 * <p>OJO con los dos "fines", que son distintos y se confunden: el que se PUBLICA es el martes
 * INCLUSIVE (la etiqueta que imprime la pantalla) y el que se CONSULTA es el miercoles siguiente
 * EXCLUSIVE. Devolver el segundo donde va el primero es el error mas facil de cometer aca.
 */
public final class OperationsWeekCycle {

    /** El dia en que abre el ciclo. No es lunes: la semana operativa arranca el miercoles. */
    private static final DayOfWeek CYCLE_OPENS_ON = DayOfWeek.WEDNESDAY;

    /** Dias a sumar al miercoles que abre para llegar al martes que cierra. El ciclo dura 7. */
    private static final int CYCLE_LAST_DAY_OFFSET = 6;

    private OperationsWeekCycle() {
    }

    /** El ciclo vigente ahora. */
    public static ServiceWeekCycleSummary current() {
        return at(Instant.now());
    }

    /**
     * El ciclo al que pertenece un instante dado. Recibe el instante y no la fecha para que el
     * huso quede resuelto ACA: pasarle un {@code LocalDate} obligaria a quien llama a decidir en
     * que zona lo calculo, que es exactamente donde se equivoca el sistema anterior.
     */
    public static ServiceWeekCycleSummary at(Instant instant) {
        LocalDate limaDate = instant.atZone(DateUtils.LIMA).toLocalDate();
        LocalDate start = limaDate.with(TemporalAdjusters.previousOrSame(CYCLE_OPENS_ON));
        return new ServiceWeekCycleSummary(start, start.plusDays(CYCLE_LAST_DAY_OFFSET));
    }

    /**
     * El ciclo que ABRE en la fecha dada, que tiene que ser un miercoles.
     *
     * <p>Es la puerta del reporte semanal, que ya recibe el miercoles del cliente y lo valido en el
     * mapper. Existe aparte de {@link #at} porque pasar por ahi obligaba a fabricar un instante para
     * volver a derivar el mismo dia: computacion redundante cuya rotura es INOBSERVABLE (correr ese
     * instante uno o seis dias devuelve el mismo ciclo, porque {@code at} retrocede al miercoles),
     * o sea una linea que ninguna prueba podia fijar. Aca el ciclo sale del dia PEDIDO, directo.
     *
     * <p>Si la fecha no es miercoles, esto es un error de PROGRAMACION —el 400 que ve el cliente lo
     * hace el mapper, antes— y por eso sale como el 500 de la casa y no como un
     * {@code IllegalArgumentException}: sin mapper para esa excepcion, el cliente recibiria un 500
     * pelado, sin {@code code} ni {@code detail}, que es justo lo que el resto del endpoint evita.
     *
     * <p>⚠️ Esta guarda da 500 y la del mapper da 400 para la MISMA invariante, a proposito: aca el
     * no-miercoles solo puede venir de un bug, alla del cliente. Un consumidor nuevo que llame a este
     * metodo sin validar antes le va a dar 500 a un usuario donde el contrato promete 400: la
     * validacion del borde es responsabilidad de quien recibe el parametro.
     */
    public static ServiceWeekCycleSummary startingOn(LocalDate cycleStart) {
        if (cycleStart.getDayOfWeek() != CYCLE_OPENS_ON) {
            throw CommonError.INTERNAL_ERROR.toException(
                "el ciclo operativo abre en miercoles, y se pidio " + cycleStart);
        }
        return new ServiceWeekCycleSummary(cycleStart, cycleStart.plusDays(CYCLE_LAST_DAY_OFFSET));
    }

    /** Borde INFERIOR de la consulta, inclusivo: el miercoles a las 00:00 de Lima. */
    public static OffsetDateTime startInstant(ServiceWeekCycleSummary weekCycle) {
        return DateUtils.limaDayStart(weekCycle.start());
    }

    /**
     * Borde SUPERIOR de la consulta, EXCLUSIVO: el miercoles siguiente a las 00:00 de Lima, o sea
     * el dia despues del martes que se publica. No es el mismo valor que {@code weekCycle.end()}.
     */
    public static OffsetDateTime endExclusiveInstant(ServiceWeekCycleSummary weekCycle) {
        return DateUtils.limaNextDayStart(weekCycle.end());
    }

    /**
     * Si el ciclo ya CERRO en un instante dado. El reporte lo publica para que la pantalla apague la
     * exportacion: un archivo de bonos de una semana abierta se lee como definitivo sin serlo.
     *
     * <p>Recibe el instante en vez de leer el reloj por el mismo motivo que {@link #at}: asi se
     * puede probar con instantes literales. Escrito con {@code Instant.now()} adentro, el unico
     * borde que importa —el momento exacto en que la semana cierra— solo se podria discriminar el
     * dia de la semana en que la suite corriera, y una mutacion que corra el corte un dia
     * sobreviviria cualquier caso que use una semana futura o una de hace siete dias.
     *
     * <p>El corte es el borde EXCLUSIVO y no el martes que se publica: son dos fechas separadas por
     * un dia, y usar la segunda daria por cerrada la semana durante todo su ultimo dia.
     */
    public static boolean hasClosed(ServiceWeekCycleSummary weekCycle, Instant now) {
        return !endExclusiveInstant(weekCycle).toInstant().isAfter(now);
    }
}
