package com.scaramutti.tms.operations.dto.embedded;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Vista del cliente para embeber en las respuestas del servicio de transporte. Incluye el
 * contacto (telefono y persona) porque el despacho llama al cliente desde el detalle del
 * viaje; en cotizaciones, en cambio, el resumen del cliente se queda en id/nombre/RUC porque
 * ahi el contacto se persiste aparte como snapshot de la cotizacion.
 *
 * <p>Los datos son LIVE, no snapshot: si el cliente actualiza su telefono, los servicios ya
 * registrados muestran el nuevo.
 */
public record ServiceClientSummary(

    @Schema(description = "ID interno del cliente", example = "12")
    Integer id,

    @Schema(description = "Razon social del cliente", example = "IPH SAC")
    String name,

    @Schema(description = "RUC del cliente (11 digitos)", example = "20123456789")
    String ruc,

    @Schema(nullable = true, description = "Telefono de contacto (9 digitos)", example = "987654321")
    String phone,

    @Schema(nullable = true, description = "Persona de contacto", example = "Maria Rojas")
    String contactName
) {}
