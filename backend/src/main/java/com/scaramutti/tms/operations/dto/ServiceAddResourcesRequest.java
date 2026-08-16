package com.scaramutti.tms.operations.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Recursos de REFUERZO que se le suman a un viaje que ya esta en ruta: el relevo de un conductor
 * que agoto su descanso, la unidad de apoyo que sale a un varado.
 *
 * <p>Los tres son opcionales por separado pero al menos uno tiene que venir. Esa condicion NO se
 * puede declarar con anotaciones —es una regla ENTRE campos, no de ninguno de ellos— asi que la
 * valida el mapper, y por eso tampoco figura en el esquema.
 *
 * <p>No reemplazan a los recursos principales: se suman. El viaje se queda en ruta y su conductor,
 * tracto y carreta originales no se tocan.
 */
public record ServiceAddResourcesRequest(

    @Schema(nullable = true, description = "Conductor de refuerzo (relevo)")
    @Positive Integer driverId,

    @Schema(nullable = true, description = "Tracto de refuerzo (apoyo)")
    @Positive Integer tractorId,

    @Schema(nullable = true, description = "Carreta de refuerzo (apoyo)")
    @Positive Integer trailerId,

    /*
     * El motivo es OBLIGATORIO, a diferencia de la nota de la asignacion principal: sumar un
     * refuerzo siempre responde a algo que paso en ruta, y eso es justamente lo que el reporte
     * semanal va a mostrar al lado de cada conductor adicional cuando exista. Sin motivo, esa
     * columna nace vacia.
     */
    @Schema(description = "Por qué se suma el refuerzo; queda en la bitácora y en la auditoría")
    @NotBlank @Size(min = MIN_REASON_LENGTH, max = 500) String reason,

    @Schema(type = SchemaType.BOOLEAN, nullable = true,
        description = "Suma el recurso pese al conflicto OPS-002 y lo deja registrado; ausente equivale a false. NO abre el duplicado del mismo viaje (OPS-003), que es duro")
    String force
) {

    /**
     * Minimo del motivo. Propio y no compartido con el de la transicion ni con el de la
     * justificacion de la edicion: hoy los tres valen 10 por coincidencia, pero son reglas de
     * contratos distintos y compartir la constante acopla cosas que pueden divergir sin aviso.
     */
    public static final int MIN_REASON_LENGTH = 10;
}
