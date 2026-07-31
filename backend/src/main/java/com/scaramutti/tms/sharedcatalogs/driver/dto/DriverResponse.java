package com.scaramutti.tms.sharedcatalogs.driver.dto;

import com.scaramutti.tms.sharedcatalogs.model.FleetResourceStatus;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Conductor del catalogo compartido {@code public.drivers} (GET /drivers). El {@code id} es
 * el que guarda la asignacion del servicio, y el {@code fullName} sale del trabajador
 * asociado: la tabla de conductores solo tiene la licencia.
 *
 * <p>Record PLANO (no anida {@code DriverRef}): el contrato lo modela con {@code allOf}
 * (DriverRef + licencia/telefono/disponibilidad/isActive), que aplana los campos.
 * {@code status} NUNCA es null aca (a diferencia de las escoltas en /fleet-units): un
 * conductor siempre tiene disponibilidad, la columna es obligatoria.
 */
public record DriverResponse(
    @Schema(example = "8") Integer id,
    @Schema(example = "Juan Pérez Huamán") String fullName,
    @Schema(example = "Q12345678") String licenseNumber,
    @Schema(example = "A-IIIc", nullable = true) String licenseCategory,
    @Schema(example = "987654321", nullable = true) String phone,
    FleetResourceStatus status,
    Boolean isActive
) {}
