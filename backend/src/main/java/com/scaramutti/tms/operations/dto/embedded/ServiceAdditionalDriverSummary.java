package com.scaramutti.tms.operations.dto.embedded;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Un conductor sumado EN RUTA a un viaje, con el motivo del relevo, tal como lo muestra el reporte.
 *
 * <p>No reusa {@code ServiceAdditionalResourceResponse} —el refuerzo tal como lo publica el
 * detalle— porque ese lleva los tres tipos de recurso, quien los asigno y cuando, y el reporte solo
 * publica el NOMBRE y el MOTIVO. Traer el otro obligaria a que la fila del reporte cargue tractos,
 * carretas y datos de auditoria que su contrato no declara.
 */
@Schema(description = "Conductor sumado en ruta, con el motivo del relevo")
public record ServiceAdditionalDriverSummary(
    String name,
    String reason
) {
}
