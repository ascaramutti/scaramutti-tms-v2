package com.scaramutti.tms.warehouse.kardex.dto;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.warehouse.kardex.model.WarehouseKardexMovementType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Una fila del kardex (GET /warehouse/products/{id}/kardex). Mapea 1:1 al
 * schema {@code WarehouseKardexMovementResponse} del contrato.
 */
public record WarehouseKardexMovementResponse(

    @Schema(description = "Tipo de movimiento")
    WarehouseKardexMovementType movementType,

    @Schema(description = "Cantidad, SIEMPRE positiva (el signo lo da movementType)")
    BigDecimal quantity,

    @Schema(description = "Saldo corrido DESPUES del movimiento, sobre la historia completa")
    BigDecimal balance,

    @Schema(description = "Fecha/hora del movimiento")
    OffsetDateTime movedAt,

    @Schema(description = "Id del origen: factura (ENTRADA) o retiro (SALIDA); null para APERTURA")
    Integer sourceId,

    @Schema(description = "Etiqueta legible compuesta por el backend (es-PE)")
    String reference,

    @Schema(description = "Usuario que registro el movimiento")
    UserResponse registeredBy
) {}
