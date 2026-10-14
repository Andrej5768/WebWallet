package com.andrewcorp.webwallet.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A new password: at least 8 characters and at most 72 bytes in UTF-8, the most BCrypt can hash (a Cyrillic
 * letter takes two bytes). Null is valid.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordValidator.class)
public @interface Password {

    String message() default "{error.password}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
