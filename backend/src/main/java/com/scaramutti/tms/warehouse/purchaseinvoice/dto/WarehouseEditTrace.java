package com.scaramutti.tms.warehouse.purchaseinvoice.dto;

import com.scaramutti.tms.auth.dto.UserResponse;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * Rastro del último cambio de un registro (quién/cuándo/motivo), derivado del
 * {@code FIELD_EDIT} más reciente en {@code almacen.audit_logs}. Viaja null hasta
 * la primera edición; lo puebla cada PUT. Forma fijada por el contrato
 * ({@code WarehouseEditTrace}).
 */
public record WarehouseEditTrace(
    UserResponse by,
    OffsetDateTime at,
    @Schema(description = "Justificación dada al editar (>= 10 caracteres)") String reason
) {}
