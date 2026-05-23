package com.scaramutti.tms.quotations.dto.embedded;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Vista resumida del tipo de carga para embeber en items de
 * {@code QuotationResponse}. Subset minimo: id + name.
 *
 * <p>Las dimensiones del catalogo (standardWeight/Length/Width/Height) NO se
 * incluyen aca: el item de la cotizacion persiste su propio
 * {@code weightKg/lengthMeters/widthMeters/heightMeters} como snapshot (el
 * frontend precarga desde el catalogo pero el usuario puede modificarlos).
 * Exponer los standardX del catalogo en este Summary mezclaria "valor de
 * referencia del catalogo" con "valor efectivo del item" — el primero sigue
 * disponible en {@code GET /catalogs/cargo-types/{id}} si se necesita.
 *
 * <p>NO incluye {@code description} ni {@code isActive}: no aportan en el
 * contexto de cotizacion.
 */
public record QuotationCargoTypeSummary(

    @Schema(description = "ID interno", example = "1")
    Integer id,

    @Schema(description = "Nombre del tipo de carga (en mayusculas)", example = "EXCAVADORA 336")
    String name
) {}
