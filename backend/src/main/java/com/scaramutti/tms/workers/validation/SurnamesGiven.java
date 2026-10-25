package com.scaramutti.tms.workers.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * El alta y la edicion traen el apellido paterno, o solo el apellido de antes ({@code lastName}).
 * Es de clase porque mira tres campos; sin ninguno de los dos, o con materno y sin paterno, el
 * error va al paterno, que es el campo que el cuerpo tiene que traer.
 */
@Documented
@Constraint(validatedBy = SurnamesGivenValidator.class)
@Target(TYPE)
@Retention(RUNTIME)
public @interface SurnamesGiven {

    String message() default "Indica el apellido paterno.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
