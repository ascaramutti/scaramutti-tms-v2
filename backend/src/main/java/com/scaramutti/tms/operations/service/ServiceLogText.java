package com.scaramutti.tms.operations.service;

import com.scaramutti.tms.shared.util.DateUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Como se escribe en la bitacora del viaje un texto que puso una persona.
 *
 * <p>Existe por una razon de seguridad, no de formato: la nota de cada entrada tiene UNA linea
 * por dato, asi que un texto libre con saltos de linea —que son legitimos y la columna los
 * guarda— permitiria plantar lineas FALSAS con el mismo formato que las que escribe el servidor.
 * Un {@code "ok\nPrecio: 3200 → 300"} en las observaciones se leeria en el detalle como si el
 * sistema hubiera registrado ese cambio, y la bitacora es justamente el rastro de rendicion de
 * cuentas: nadie puede editarla ni borrarla despues.
 *
 * <p>El texto EXACTO no se pierde: queda en la columna del viaje y en la auditoria, que son el
 * registro reconstruible. Aca solo se aplana lo que se MUESTRA.
 *
 * <p>Es una clase compartida y no un metodo privado porque la aplican la edicion y la asignacion,
 * y la va a aplicar cada endpoint que sume una entrada con texto del usuario. Con una copia por
 * endpoint, al primero que se le olvide reabre el agujero para todos.
 */
public final class ServiceLogText {

    /** Como se muestra un campo que estaba (o queda) sin valor. */
    public static final String EMPTY_VALUE_LABEL = "(vacío)";

    private ServiceLogText() {}

    /**
     * Aplasta los saltos de linea a un simbolo que se ve pero no corta la linea. Devuelve null
     * para null: quien llama decide si un texto ausente se nombra o se omite, que no es lo mismo
     * en todos los endpoints.
     *
     * <p>{@code \R} cubre todas las formas de salto que reconoce Java, no solo {@code \n}: un
     * retorno de carro suelto o un separador de linea de Unicode cortan la linea igual en la
     * pantalla, y dejarlos pasar seria cerrar la puerta y abrir la ventana.
     */
    public static String flattenLineBreaks(String value) {
        return value == null ? null : value.replaceAll("\\R", " ⏎ ");
    }

    /** El valor ya listo para mostrar: el vacio se NOMBRA, porque "Alto (m): → 12" no se entiende. */
    public static String display(String value) {
        return value == null ? EMPTY_VALUE_LABEL : flattenLineBreaks(value);
    }

    /**
     * Como se GUARDA una marca de tiempo en la auditoria: siempre en UTC, que es el marco en el
     * que la columna la almacena.
     *
     * <p>Vive aca y no en cada servicio porque las mismas dos columnas —el inicio y el fin reales—
     * las escriben ya dos endpoints, la edicion y la transicion de estado. La auditoria existe para
     * reconstruir el estado comparando el valor anterior con el nuevo, asi que si cada puerta
     * canonicaliza distinto, dos filas que describen el MISMO instante dejan de ser comparables y
     * el rastro pierde justamente lo que lo hace util.
     */
    public static String asUtcText(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toString();
    }

    /**
     * La misma marca, en hora de Peru, para que una persona la lea sin traducir el huso.
     *
     * <p>La bitacora la lee gente y la auditoria la lee el sistema: por eso son dos formatos y no
     * uno. En UTC la misma pantalla diria dos horas distintas para el mismo dato segun quien la
     * mire, que es peor que no mostrarla.
     */
    public static String asLimaText(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(DateUtils.LIMA).format(LIMA_FORMAT);
    }

    /**
     * Recorta el texto que un mensaje de error DEVUELVE al que lo mando: no hay por que reflejarlo
     * entero. Vive aca desde que lo usan dos capas —el mapeo del pedido y la resolucion del destino
     * de la reapertura, que refleja un valor traido de la base— y no un solo metodo privado.
     *
     * <p>El corte cuenta CARACTERES, no unidades de codigo: un emoji ocupa dos unidades y cortarlo
     * al medio deja media pareja suelta, que ya no es texto valido (un lector estricto rechaza la
     * respuesta y la interfaz muestra un rombo).
     */
    public static String abbreviate(String value) {
        if (value == null || value.codePointCount(0, value.length()) <= MAX_ECHOED_CHARS) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_ECHOED_CHARS)) + "…";
    }

    /** Tope de caracteres del texto que se refleja en un mensaje de error. */
    private static final int MAX_ECHOED_CHARS = 30;

    /** Formato de las marcas de tiempo en la bitacora, en hora de Peru y como se lee en es-PE. */
    private static final DateTimeFormatter LIMA_FORMAT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
}
