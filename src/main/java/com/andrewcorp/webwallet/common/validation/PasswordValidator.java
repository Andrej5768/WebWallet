package com.andrewcorp.webwallet.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public class PasswordValidator implements ConstraintValidator<Password, String> {

    static final int MIN_LENGTH = 8;
    static final int MAX_BYTES = 72;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null
                || (value.codePointCount(0, value.length()) >= MIN_LENGTH
                        && value.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
    }
}
