package com.scaramutti.tms.operations.dto.embedded;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Vista del tipo de carga para embeber en las respuestas del servicio. Subset minimo: el viaje
 * persiste su propio peso y sus propias dimensiones, asi que las medidas estandar del catalogo
 * no aportan aca (mezclarian "referencia del catalogo" con "valor efectivo del viaje").
 */
public record ServiceCargoTypeSummary(

    @Schema(description = "ID interno", example = "3")
    Integer id,

    @Schema(description = "Nombre del tipo de carga", example = "CONTENEDOR 40")
    String name
) {}
