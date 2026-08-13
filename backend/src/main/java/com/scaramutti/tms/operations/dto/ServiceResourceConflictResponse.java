package com.scaramutti.tms.operations.dto;

import com.scaramutti.tms.operations.model.ServiceResourceKind;
import com.scaramutti.tms.operations.model.ServiceStatus;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Un recurso pedido que ya esta retenido por OTRO viaje activo. Viaja dentro del cuerpo del
 * error OPS-002, en su miembro de extension {@code conflicts}.
 *
 * <p>Lleva el NOMBRE del recurso y no solo su id porque el destinatario es la persona que decide
 * si fuerza la asignacion: "el conductor 47 esta ocupado" no le permite decidir nada. El codigo y
 * el estado son los del viaje que lo retiene, no los del que se esta asignando.
 */
public record ServiceResourceConflictResponse(

    ServiceResourceKind resource,

    @Schema(description = "Nombre del conductor o placa de la unidad", example = "Juan Pérez Huamán")
    String resourceName,

    @Schema(description = "Código del viaje que retiene el recurso", example = "SRV-0042")
    String serviceCode,

    @Schema(description = "Solo PENDING_START o IN_PROGRESS: antes de asignar no hay recursos, y los estados terminales ya los liberaron")
    ServiceStatus serviceStatus
) {}
