package com.andrewcorp.webwallet.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ISO 4217 currency code, e.g. {@code UAH}. Null is valid.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CurrencyCodeValidator.class)
public @interface CurrencyCode {

    String message() default "{error.currency}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
