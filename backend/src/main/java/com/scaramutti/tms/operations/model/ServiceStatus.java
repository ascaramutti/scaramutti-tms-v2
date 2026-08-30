package com.scaramutti.tms.operations.model;

/**
 * Ciclo de vida del servicio de transporte. En la BD es una columna VARCHAR respaldada por
 * el CHECK {@code chk_services_status} (V007, ampliado con DELETED por V008); en la API viaja
 * en MAYUSCULAS, mismo patron que {@code QuotationStatus}.
 *
 * <p>Las transiciones validas las gobierna el endpoint de estado: desde
 * los dos pendientes se puede eliminar, desde los tres no terminales cancelar, y el camino
 * feliz es {@code PENDING_ASSIGNMENT → PENDING_START → IN_PROGRESS → COMPLETED}. El paso a
 * {@code PENDING_START} no se pide: es efecto de asignar recursos. Y hay un arco que va hacia
 * ATRAS: la REAPERTURA saca al viaje de cancelado o eliminado y lo devuelve al estado que tenia.
 */
public enum ServiceStatus {

    /** Registrado, todavia sin conductor ni unidad. Estado en el que nace todo servicio. */
    PENDING_ASSIGNMENT,

    /** Con recursos asignados, esperando salir. */
    PENDING_START,

    /** En ruta. */
    IN_PROGRESS,

    /** Terminado; sigue siendo editable para corregir datos con justificacion. */
    COMPLETED,

    /**
     * Viaje real que se aborto. Terminal para el AVANCE —no hay transicion que lo mueva hacia
     * adelante— e inmutable para toda escritura, con una unica excepcion: se puede REABRIR, y ahi
     * vuelve al estado que tenia antes. Terminal para la maquina y inmutable para la fila son dos
     * preguntas distintas, y esta es la que las separa.
     */
    CANCELLED,

    /**
     * Registro que nunca debio existir (error de digitacion). Mismas reglas que CANCELLED,
     * incluida la reapertura. Distinto de CANCELLED para que la tasa de cancelacion siga siendo
     * una metrica de negocio limpia. Queda fuera de listados e indicadores salvo que se pida
     * explicitamente.
     */
    DELETED
}
