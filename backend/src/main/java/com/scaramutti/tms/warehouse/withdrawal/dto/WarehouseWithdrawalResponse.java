package com.scaramutti.tms.warehouse.withdrawal.dto;

import com.scaramutti.tms.auth.dto.UserResponse;
import com.scaramutti.tms.shared.dto.WorkerResponse;
import com.scaramutti.tms.sharedcatalogs.fleetunit.dto.FleetUnitRef;
import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;
import com.scaramutti.tms.warehouse.product.dto.WarehouseProductSummary;
import com.scaramutti.tms.warehouse.purchaseinvoice.dto.WarehouseEditTrace;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Detalle de un retiro (respuesta del POST y del listado). {@code fleetUnit} es la unidad
 * destino (a lo sumo una) o null. Los campos de anulación y {@code lastEdit} viajan null
 * mientras el retiro esté ACTIVE y sin ediciones; los pueblan la anulación y la edición.
 * {@code updatedAt} es la versión del ETag (en el alta = {@code withdrawnAt}).
 */
public record WarehouseWithdrawalResponse(
    Integer id,
    WarehouseProductSummary product,
    BigDecimal quantity,
    OffsetDateTime withdrawnAt,
    WorkerResponse receivedBy,
    @Schema(nullable = true) FleetUnitRef fleetUnit,
    @Schema(nullable = true) String observations,
    WarehouseRecordStatus status,
    @Schema(nullable = true) String cancelReason,
    @Schema(nullable = true) UserResponse cancelledBy,
    @Schema(nullable = true) OffsetDateTime cancelledAt,
    @Schema(nullable = true) WarehouseEditTrace lastEdit,
    UserResponse registeredBy,
    @Schema(description = "Fuente de la versión; usar el header ETag opaco en If-Match, NO este valor") OffsetDateTime updatedAt
) {}
