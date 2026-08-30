package com.scaramutti.tms.operations.dto;

import com.scaramutti.tms.operations.dto.embedded.ServiceWeekCycleSummary;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * El reporte de facturacion de UNA semana operativa: las filas y sus totales por moneda.
 *
 * <p>Se pide de a una semana y nunca por rango libre. El motivo no es tecnico: el archivo existe
 * para calcular bonos, y un reporte que mezcla semanas no sirve para eso. El sistema anterior lo
 * pide igual (por numero de semana), y ahi la regla es evidente; con un rango libre habria que
 * decidir que significa "semana cerrada" para un pedido que abarca tres.
 *
 * <p>La semana viaja en la respuesta —y no solo en la peticion— para que el documento que se
 * imprime diga a que periodo corresponde sin depender de la pantalla que lo pidio.
 *
 * <p>Las dos listas NUNCA son null: una semana sin viajes devuelve las dos vacias. Un null obligaria
 * a todo consumidor a distinguir "no hubo" de "no vino", que son la misma cosa aca.
 */
@Schema(description = "Viajes completados en una semana operativa, con sus totales por moneda")
public record ServicesReportResponse(

    @Schema(description = "La semana reportada. `end` es el martes INCLUSIVE, no el miércoles con el que se consulta")
    ServiceWeekCycleSummary weekCycle,

    @Schema(description = "Si la semana ya cerró. La semana en curso se consulta igual y devuelve false; lo que depende de esto es la exportación")
    boolean closed,

    List<ServicesReportRowResponse> rows,

    @Schema(description = "Una fila por moneda presente en la semana. Sin conversión entre monedas")
    List<ServicesReportTotalsResponse> totals
) {
}
