package com.andrewCorp.webWallet.validation;

import com.andrewCorp.webWallet.web.dto.PasswordDto;
import com.andrewCorp.webWallet.web.dto.UserDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatorsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static UserDto validUser() {
        UserDto dto = new UserDto();
        dto.setFirstName("John");
        dto.setLastName("Doe");
        dto.setEmail("john.doe@example.com");
        dto.setPassword("password123");
        dto.setMatchingPassword("password123");
        return dto;
    }

    @Test
    void validUserHasNoViolations() {
        assertThat(validator.validate(validUser())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"john@example.com", "john+tag.doe@sub.example.org", "a_b-c@example.co"})
    void validEmails(String email) {
        assertThat(new EmailValidator().isValid(email, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "john@", "@example.com", "john@example", "john doe@example.com"})
    void invalidEmails(String email) {
        assertThat(new EmailValidator().isValid(email, null)).isFalse();
    }

    @Test
    void mismatchingPasswordsAreRejected() {
        UserDto dto = validUser();
        dto.setMatchingPassword("different123");

        Set<ConstraintViolation<UserDto>> violations = validator.validate(dto);

        assertThat(violations).extracting(ConstraintViolation::getMessage).containsExactly("Passwords don't match");
    }

    @Test
    void tooShortPasswordIsRejectedWithPassayMessage() {
        UserDto dto = validUser();
        dto.setPassword("short");
        dto.setMatchingPassword("short");

        Set<ConstraintViolation<UserDto>> violations = validator.validate(dto);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo("password");
            assertThat(violation.getMessage()).contains("8 or more characters");
        });
    }

    @Test
    void passwordDtoValidatesNewPassword() {
        PasswordDto dto = new PasswordDto();
        dto.setOldPassword("old");
        dto.setToken("token");
        dto.setNewPassword("short");

        assertThat(validator.validate(dto)).extracting(v -> v.getPropertyPath().toString()).containsExactly("newPassword");
        assertThat(dto.getOldPassword()).isEqualTo("old");
        assertThat(dto.getToken()).isEqualTo("token");

        dto.setNewPassword("longEnough1");
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void userDtoToStringDoesNotExposePassword() {
        UserDto dto = validUser();
        dto.setRole(1);

        assertThat(dto.toString())
                .contains("firstName=John", "lastName=Doe", "email=john.doe@example.com", "role=1")
                .doesNotContain("password123");
        assertThat(dto.getRole()).isEqualTo(1);
    }
}
