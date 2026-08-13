package com.scaramutti.tms.operations.service;

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
}
