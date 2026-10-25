package com.scaramutti.tms.workers.validation;

import com.scaramutti.tms.shared.util.StringUtils;
import com.scaramutti.tms.workers.dto.WorkerSurnamesBody;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class SurnamesGivenValidator implements ConstraintValidator<SurnamesGiven, WorkerSurnamesBody> {

    @Override
    public boolean isValid(WorkerSurnamesBody surnamesBody, ConstraintValidatorContext context) {
        // Un cuerpo nulo lo rechaza el @NotNull del recurso; un paterno que no cumple, su regla.
        if (surnamesBody == null || surnamesBody.paternalLastName() != null) {
            return true;
        }
        // El materno cuenta como lo deja el mapper: vacio o de espacios es nulo.
        boolean withMaternal = StringUtils.trimToNull(surnamesBody.maternalLastName()) != null;
        if (surnamesBody.lastName() != null && !withMaternal) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
            .addPropertyNode("paternalLastName")
            .addConstraintViolation();
        return false;
    }
}
