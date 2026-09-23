package com.scaramutti.tms.workers.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * El cuerpo de la edicion: los mismos campos del alta mas el motivo.
 *
 * <p>Es un REEMPLAZO y no un parche. Un campo opcional que no viene queda VACIO: omitir el
 * telefono lo borra. Quien lea esto buscando por que no hay comprobaciones de nulo antes de
 * asignar, la respuesta es esa.
 *
 * <p>Repite los ocho campos del alta en vez de heredarlos, y no es un descuido: un record no
 * hereda, y anidar el cuerpo del alta cambiaria la forma del JSON que el contrato declara. Es el
 * molde del modulo de operaciones, que tiene el mismo par. El riesgo conocido es que las dos
 * formas se separen sin que nada deje de compilar, y por eso hay un caso que compara los
 * componentes de los dos records.
 *
 * <p>El minimo del motivo NO esta aca a proposito. Es condicional —obligatorio solo cuando el
 * numero de documento cambia respecto de lo guardado— y el borde corre antes que el servicio: con
 * el minimo declarado aca, un motivo corto saldria como error de forma y el codigo de negocio no
 * podria emitirse nunca, y ademas se lo exigiria en ediciones donde el contrato dice que es libre.
 * El maximo si vive aca: ese no depende de nada.
 *
 * <p>{@code isActive}, {@code createdAt} y {@code createdBy} no son componentes de este record,
 * que es como se impide que el cuerpo los mueva. Mismo criterio que el alta.
 */
public record WorkerUpdateRequest(
    @NotBlank @Size(max = 100) String firstName,
    @NotBlank @Size(max = 100) String lastName,
    @NotNull Integer documentTypeId,
    @NotBlank @Size(max = 20) String documentNumber,
    @Pattern(regexp = "^\\d{9}$") String phone,
    @NotBlank @Size(max = 50) String role,
    @NotNull LocalDate hireDate,
    @Valid WorkerDriverProfileRequest driver,
    @Size(max = 500) String reason
) {
    /** El piso del motivo, medido en el servicio por lo que explica el javadoc de arriba. */
    public static final int MIN_REASON_LENGTH = 10;
}
