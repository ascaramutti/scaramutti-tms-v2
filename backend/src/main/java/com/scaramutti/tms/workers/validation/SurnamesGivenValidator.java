package com.scaramutti.tms.workers.validation;

import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.workers.dto.WorkerRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class SurnamesGivenValidator implements ConstraintValidator<SurnamesGiven, WorkerRequest> {

    @Override
    public boolean isValid(WorkerRequest workerRequest, ConstraintValidatorContext context) {
        // Un cuerpo nulo lo rechaza el @NotNull del recurso; un paterno que no cumple, su regla.
        if (workerRequest == null || workerRequest.paternalLastName() != null) {
            return true;
        }
        // El materno cuenta como lo deja el mapper: vacio o de espacios es nulo.
        boolean withMaternal = StringUtils.trimToNull(workerRequest.maternalLastName()) != null;
        if (workerRequest.lastName() != null && !withMaternal) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
            .addPropertyNode("paternalLastName")
            .addConstraintViolation();
        return false;
    }
}
