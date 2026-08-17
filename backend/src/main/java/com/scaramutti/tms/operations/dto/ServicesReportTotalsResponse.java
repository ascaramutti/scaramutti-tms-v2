package com.scaramutti.tms.operations.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Totales de UNA moneda. Va una fila por moneda presente en la semana y NO se convierte nada: sumar
 * soles con dolares daria un numero que no significa nada, y elegir un tipo de cambio seria una
 * decision contable que este endpoint no puede tomar. El reporte de almacen comparte ese criterio
 * de NO convertir; la forma, en cambio, es distinta (alli son dos campos fijos por moneda, que
 * publican el cero de la que falta).
 *
 * <p>Solo aparecen las monedas que la semana TIENE. Una semana sin viajes devuelve la lista vacia, no
 * una fila en cero por cada moneda del catalogo: el catalogo puede crecer y el reporte quedaria
 * publicando monedas que la empresa nunca uso.
 */
@Schema(description = "Totales de una moneda, sin conversión")
public record ServicesReportTotalsResponse(

    String currencyCode,

    @Schema(description = "Cuántos viajes se cobraron en esta moneda")
    int totalServices,

    @Schema(description = "Suma de los importes de esta moneda")
    BigDecimal totalRevenue
) {
}
