package com.andrewCorp.webWallet.web.error;

import com.andrewCorp.webWallet.web.dto.UserDto;
import com.andrewCorp.webWallet.web.util.GenericResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class RestResponseEntityExceptionHandlerTest {

    private RestResponseEntityExceptionHandler handler;
    private ServletWebRequest request;

    @BeforeEach
    void setUp() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("message.invalidReCaptcha", Locale.ENGLISH, "Invalid reCaptcha");
        messages.addMessage("message.unavailableReCaptcha", Locale.ENGLISH, "reCaptcha unavailable");
        messages.addMessage("message.userNotFound", Locale.ENGLISH, "User not found");
        messages.addMessage("message.email.config.error", Locale.ENGLISH, "Mail error");
        messages.addMessage("message.error", Locale.ENGLISH, "Error");

        handler = new RestResponseEntityExceptionHandler();
        ReflectionTestUtils.setField(handler, "messages", messages);

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addPreferredLocale(Locale.ENGLISH);
        request = new ServletWebRequest(servletRequest);
    }

    private static GenericResponse body(ResponseEntity<Object> response) {
        return (GenericResponse) response.getBody();
    }

    @Test
    void bindException() {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new UserDto(), "userDto");
        result.reject("global", "Passwords don't match");
        result.rejectValue("email", "invalid", "Invalid email");

        ResponseEntity<Object> response = handler.handleBindException(new BindException(result), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).getError()).isEqualTo("InvaliduserDto");
        assertThat(body(response).getMessage())
                .contains("{\"object\":\"userDto\",\"defaultMessage\":\"Passwords don't match\"}")
                .contains("{\"field\":\"email\",\"defaultMessage\":\"Invalid email\"}");
    }

    @Test
    void reCaptchaInvalid() {
        ResponseEntity<Object> response = handler.handleReCaptchaInvalid(new ReCaptchaInvalidException("bad"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).getMessage()).isEqualTo("Invalid reCaptcha");
    }

    @Test
    void reCaptchaUnavailable() {
        ResponseEntity<Object> response = handler.handleReCaptchaUnavailable(new ReCaptchaUnavailableException("down"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(response).getMessage()).isEqualTo("reCaptcha unavailable");
    }

    @Test
    void userNotFound() {
        ResponseEntity<Object> response = handler.handleUserNotFound(new UserNotFoundException("missing"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(response).getError()).isEqualTo("UserNotFound");
    }

    @Test
    void mailError() {
        ResponseEntity<Object> response = handler.handleMail(new MailAuthenticationException("auth failed"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(response).getError()).isEqualTo("MailError");
    }

    @Test
    void internalError() {
        ResponseEntity<Object> response = handler.handleInternal(new IllegalStateException("boom"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(response).getMessage()).isEqualTo("Error");
    }

    @Test
    void exceptionsKeepMessageAndCause() {
        Throwable cause = new IllegalArgumentException("cause");

        assertThat(new UserNotFoundException()).hasMessage(null);
        assertThat(new UserNotFoundException("msg", cause)).hasMessage("msg").hasCause(cause);
        assertThat(new UserNotFoundException(cause)).hasCause(cause);
        assertThat(new UserAlreadyExistException()).hasMessage(null);
        assertThat(new UserAlreadyExistException("msg", cause)).hasMessage("msg").hasCause(cause);
        assertThat(new UserAlreadyExistException(cause)).hasCause(cause);
        assertThat(new InvalidOldPasswordException("msg")).hasMessage("msg");
        assertThat(new InvalidOldPasswordException("msg", cause)).hasCause(cause);
        assertThat(new InvalidOldPasswordException(cause)).hasCause(cause);
        assertThat(new ReCaptchaInvalidException()).hasMessage(null);
        assertThat(new ReCaptchaInvalidException("msg", cause)).hasCause(cause);
        assertThat(new ReCaptchaInvalidException(cause)).hasCause(cause);
        assertThat(new ReCaptchaUnavailableException()).hasMessage(null);
        assertThat(new ReCaptchaUnavailableException("msg", cause)).hasCause(cause);
        assertThat(new ReCaptchaUnavailableException(cause)).hasCause(cause);
        assertThat(new UnusualLocationException("msg")).hasMessage("msg");
        assertThat(new UnusualLocationException("msg", cause)).hasCause(cause);
    }
}
