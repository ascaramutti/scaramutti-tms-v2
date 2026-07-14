package com.scaramutti.tms.warehouse.withdrawal.service.cmd;

import com.scaramutti.tms.warehouse.model.WarehouseRecordStatus;

import java.time.LocalDate;

/**
 * Filtros del listado de retiros. {@code status} null = trae ACTIVOS y ANULADOS.
 * {@code dateFrom}/{@code dateTo} filtran por {@code withdrawnAt} (rango inclusive, día
 * completo en zona América/Lima).
 */
public record ListWarehouseWithdrawalsQuery(
    Integer productId,
    Integer receivedByWorkerId,
    Integer tractorId,
    Integer trailerId,
    Integer escortVehicleId,
    WarehouseRecordStatus status,
    LocalDate dateFrom,
    LocalDate dateTo,
    int page,
    int size
) {}
