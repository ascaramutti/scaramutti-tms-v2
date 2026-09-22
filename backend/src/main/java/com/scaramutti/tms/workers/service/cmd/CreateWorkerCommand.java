package com.scaramutti.tms.workers.service.cmd;

import java.time.LocalDate;

/**
 * El alta de un trabajador, ya normalizada: los textos llegan recortados y los que quedaron
 * vacios llegan nulos.
 *
 * <p>El cargo viaja como NOMBRE y no como id: es el identificador estable de la jerarquia y
 * es lo que el catalogo de cargos publica, asi que el formulario manda lo mismo que leyo.
 */
public record CreateWorkerCommand(
    String firstName,
    String lastName,
    Integer documentTypeId,
    String documentNumber,
    String phone,
    String role,
    LocalDate hireDate,
    WorkerDriverProfileCommand driver
) {}
