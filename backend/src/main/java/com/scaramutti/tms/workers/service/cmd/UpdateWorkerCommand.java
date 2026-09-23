package com.scaramutti.tms.workers.service.cmd;

import java.time.LocalDate;

/**
 * La edicion de un trabajador, ya normalizada: los textos llegan recortados y los que quedaron
 * vacios llegan nulos, el motivo incluido.
 *
 * <p>Que el motivo llegue nulo por venir en blanco es parte de la regla y no un efecto colateral:
 * diez espacios no son una justificacion de por que cambio el numero de documento de alguien.
 */
public record UpdateWorkerCommand(
    Integer workerId,
    String firstName,
    String lastName,
    Integer documentTypeId,
    String documentNumber,
    String phone,
    String role,
    LocalDate hireDate,
    WorkerDriverProfileCommand driver,
    String reason
) {}
