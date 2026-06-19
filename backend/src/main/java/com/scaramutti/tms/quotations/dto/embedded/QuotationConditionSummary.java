package com.scaramutti.tms.quotations.dto.embedded;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Vista de una condicion general LINKEADA a una cotizacion, para embeber en
 * {@code QuotationResponse.conditions}.
 *
 * <p>A diferencia de los otros Summary, este SI expone {@code isActive}: el detalle/PDF
 * resuelve TODAS las condiciones linkeadas — activas E inactivas — para preservar el
 * snapshot historico (RN-05). Una condicion desactivada en el catalogo despues de emitirse
 * la cotizacion sigue apareciendo aca con {@code isActive=false}. (En el contrato OpenAPI
 * este schema se llama {@code QuotationConditionResponse}.)
 */
public record QuotationConditionSummary(

    @Schema(description = "ID interno", example = "3")
    Integer id,

    @Schema(description = "Texto de la condicion (snapshot del catalogo)")
    String text,

    @Schema(description = "Orden de impresion en el PDF (ASC, RN-04)", example = "2")
    Integer displayOrder,

    @Schema(description = "false si la condicion se desactivo en el catalogo tras linkearse (RN-05)")
    Boolean isActive
) {}
