package com.scaramutti.tms.operations.model;

/**
 * Tipo de una entrada de la bitacora del servicio ({@code operaciones.service_events}), para
 * que la interfaz pinte cada linea con su etiqueta sin tener que interpretar el texto.
 *
 * <p>Reemplaza al campo de texto concatenado del sistema anterior, donde toda la bitacora
 * vivia en una sola celda: la bitacora historica que llegue con la migracion de datos entra
 * como {@link #NOTE}.
 */
public enum ServiceEventType {

    /** Alta del servicio. */
    CREATED,

    /** Asignacion de recursos (principales o de refuerzo). */
    ASSIGNMENT,

    /** Cambio de estado (inicio, fin, cancelacion, eliminacion). */
    STATUS_CHANGE,

    /** Edicion de campos con justificacion. */
    FIELD_EDIT,

    /** Nota suelta del usuario, y la bitacora heredada del sistema anterior. */
    NOTE
}
