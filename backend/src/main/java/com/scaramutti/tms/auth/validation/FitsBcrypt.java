package com.scaramutti.tms.auth.validation;

import com.scaramutti.tms.auth.service.PasswordService;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * La contrasena cabe en BCrypt (ver {@link PasswordService#fitsBcrypt}). Va en el pedido para que
 * el login la rechace antes de buscar al usuario: la respuesta es la misma exista o no la cuenta,
 * sin depender de que el servicio iguale los tiempos, y quien llama recibe el campo.
 */
@Documented
@Constraint(validatedBy = FitsBcryptValidator.class)
@Target({ METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE })
@Retention(RUNTIME)
public @interface FitsBcrypt {

    String message() default "debe tener como maximo " + PasswordService.MAX_PASSWORD_BYTES
        + " bytes en UTF-8 (una tilde ocupa 2 y un emoji, 3 o mas), sin caracteres nulos ni surrogates sueltos";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
