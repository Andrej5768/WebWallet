package com.andrewCorp.webWallet.validation;

import com.google.common.base.Joiner;
import org.passay.DefaultPasswordValidator;
import org.passay.PasswordData;
import org.passay.PasswordValidator;
import org.passay.ValidationResult;
import org.passay.resolver.PropertiesMessageResolver;
import org.passay.rule.LengthRule;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;

public class PasswordConstraintValidator implements ConstraintValidator<ValidPassword, String> {

    @Override
    public void initialize(final ValidPassword arg0) {

    }

    @Override
    public boolean isValid(final String password, final ConstraintValidatorContext context) {
        // @formatter:off
        // Passay 2 resolves no messages unless a MessageResolver is given explicitly
        final PasswordValidator validator = new DefaultPasswordValidator(new PropertiesMessageResolver(), Arrays.asList(
            new LengthRule(8, 30)//,
//            new UppercaseCharacterRule(1),
//            new DigitCharacterRule(1),
//            new SpecialCharacterRule(1),
//            new NumericalSequenceRule(3,false),
//            new AlphabeticalSequenceRule(3,false),
//            new QwertySequenceRule(3,false),
//            new WhitespaceRule()
        ));
        final ValidationResult result = validator.validate(new PasswordData(password));
        if (result.isValid()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(Joiner.on(",").join(result.getMessages())).addConstraintViolation();
        return false;
    }

}
