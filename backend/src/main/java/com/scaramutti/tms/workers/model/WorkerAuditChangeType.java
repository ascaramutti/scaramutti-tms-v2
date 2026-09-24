package com.scaramutti.tms.workers.model;

/**
 * Que clase de cambio registra una fila de la bitacora de trabajadores. Es el dominio que la
 * columna cierra con su restriccion; se guarda por su nombre.
 *
 * <p>Vive en el modulo y no junto a la entidad para que el paquete compartido no importe nada
 * de trabajadores: la entidad guarda el texto y quien lo interpreta es este modulo.
 */
public enum WorkerAuditChangeType {

    /** Alta: una sola fila, sin campo ni valores. No hubo nada que cambiar, se creo. */
    CREATED,
    /** Edicion: una fila por campo efectivamente cambiado. */
    FIELD_EDIT,
    /** Desactivar: una fila por el trabajador y una por cada fila que la cascada apago. */
    DEACTIVATED,
    REACTIVATED
}
