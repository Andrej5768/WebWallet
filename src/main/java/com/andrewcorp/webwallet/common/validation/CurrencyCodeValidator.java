package com.andrewcorp.webwallet.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Currency;

public class CurrencyCodeValidator implements ConstraintValidator<CurrencyCode, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (!value.matches("[A-Z]{3}")) {
            return false;
        }
        try {
            Currency.getInstance(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
