package com.scaramutti.tms.auth.validation;

import com.scaramutti.tms.auth.service.PasswordService;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class FitsBcryptValidator implements ConstraintValidator<FitsBcrypt, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Un valor null lo rechaza @NotBlank: aca contaria dos veces el mismo error.
        return value == null || PasswordService.fitsBcrypt(value);
    }
}
