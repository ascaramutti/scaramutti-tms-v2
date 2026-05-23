package com.scaramutti.tms.quotations.dto.embedded;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Vista resumida del cliente para embeber en {@code QuotationResponse}.
 * Subset intencional de {@code ClientResponse}: solo los campos necesarios
 * para identificar al cliente en el contexto de cotizacion (id, name, ruc).
 *
 * <p>NO incluye {@code phone}, {@code contactName}, {@code isActive},
 * {@code createdAt}: esos pertenecen al master del cliente, no al snapshot
 * de la cotizacion. Si la cotizacion requiere telefono/contacto, esos viven
 * en {@code QuotationResponse.contactPhone} y {@code contactName}.
 *
 * <p>Patron: Anti-Corruption Layer del bounded context Quotations frente al
 * de Clients. Cambios futuros en ClientResponse no se filtran a este DTO.
 */
public record QuotationClientSummary(

    @Schema(description = "ID interno del cliente", example = "1")
    Integer id,

    @Schema(description = "Razon social del cliente", example = "ACME CORP SAC")
    String name,

    @Schema(description = "RUC del cliente (11 digitos)", example = "20123456789")
    String ruc
) {}
