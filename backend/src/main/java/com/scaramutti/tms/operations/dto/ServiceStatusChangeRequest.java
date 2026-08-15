package com.scaramutti.tms.operations.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;


/**
 * Transicion pedida sobre un viaje. {@code target} es el estado al que se quiere IR, no el actual.
 *
 * <p>El paso de "pendiente de asignacion" a "pendiente de inicio" no se pide por aca: es efecto de
 * asignar recursos, no una transicion que alguien solicite.
 */
public record ServiceStatusChangeRequest(

    /**
     * Llega como texto y no como enum a proposito. Un campo tipado que no parsea lo rechaza el
     * lector de JSON antes de que corra una sola linea nuestra, y este proyecto no tiene ningun
     * manejador de esos errores: la respuesta saldria con un cuerpo que no es el Problem de RFC
     * 7807 que el contrato promete. Resuelto en el mapper, un valor invalido es un 400 COM-001 con
     * el detalle que corresponde.
     *
     * <p>El contrato publicado NO cambia por esto: la spec se sirve del YAML y ahi estan declarados
     * los cinco valores.
     */
    @NotBlank
    @Schema(description = "Estado al que se quiere ir", enumeration = {
        "IN_PROGRESS", "COMPLETED", "CANCELLED", "DELETED", "REOPENED" }, example = "IN_PROGRESS")
    String target,

    /**
     * Inicio o fin REAL del viaje. Ausente y null significan lo mismo: ahora.
     *
     * <p>Solo aplica al iniciar y al finalizar. Mandarlo al cancelar, eliminar o reabrir es un
     * 400: esas tres no fechan el viaje sino la decision, y aceptar el dato para descartarlo en
     * silencio dejaria al cliente creyendo que guardo una marca que nunca existio.
     *
     * <p>Llega como TEXTO por el mismo motivo que {@code target}, y esta vez medido: declarado
     * como {@code OffsetDateTime}, un valor que no parsea lo rechaza el lector de JSON con un
     * cuerpo que no es RFC 7807, con content-type comun y filtrando internos del parser
     * ({@code objectName}, la linea y la columna del error). El contrato promete un Problem.
     */
    @Schema(nullable = true, type = SchemaType.STRING, format = "date-time",
        description = "Inicio o fin real; ausente o null = ahora. Solo al iniciar o finalizar",
        example = "2026-07-10T05:12:00Z")
    String dateTime,

    /**
     * Texto libre. Opcional al iniciar y al finalizar; obligatorio con {@link #MIN_NOTE_LENGTH}
     * caracteres o mas al cancelar, al eliminar y al reabrir (RN-OP7), porque en esos tres casos
     * es el motivo, y es lo unico que despues explica por que el viaje salio del circuito o volvio.
     */
    @Schema(nullable = true, description = "Nota al iniciar o finalizar; MOTIVO obligatorio (10+) al cancelar, eliminar o reabrir")
    @Size(max = 500) String note,

    /**
     * Solo aplica al REABRIR, y existe por un motivo concreto: cancelar no limpia los recursos
     * —se conservan para no perder quien estaba asignado— pero un viaje cancelado deja de
     * retenerlos, asi que en el medio otro viaje se los puede llevar. Devolver el nuestro a un
     * estado que retiene los pondria a compartirlos SIN que nadie lo haya decidido y sin la linea
     * de bitacora que dice que se forzo. Ausente equivale a false, igual que en la asignacion.
     */
    @Schema(type = SchemaType.BOOLEAN, nullable = true,
        description = "Solo al reabrir: reabre pese al conflicto OPS-002 y lo deja registrado; ausente equivale a false")
    String force
) {

    /**
     * Minimo del motivo. Propio y no compartido con la justificacion de la edicion: hoy los dos
     * valen 10 por coincidencia, pero son reglas de dos contratos distintos (aquella es siempre
     * obligatoria, esta solo en tres de las cinco transiciones) y compartir la constante acopla
     * dos cosas que pueden divergir sin aviso.
     */
    public static final int MIN_NOTE_LENGTH = 10;
}
