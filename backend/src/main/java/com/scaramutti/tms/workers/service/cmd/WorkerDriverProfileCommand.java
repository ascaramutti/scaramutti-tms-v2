package com.scaramutti.tms.workers.service.cmd;

import com.scaramutti.tms.sharedcatalogs.model.FleetResourceStatus;

/**
 * La ficha de conductor de un alta, ya recortada. Que este command sea NULO es un dato del
 * negocio y no un descuido: es la diferencia entre "el cargo no lleva ficha" y "lleva una", y
 * es lo unico con lo que el servicio decide el error de correspondencia.
 */
public record WorkerDriverProfileCommand(
    String licenseNumber,
    String licenseCategory,
    FleetResourceStatus status
) {}
