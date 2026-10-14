package com.andrewcorp.webwallet.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Renders all errors as ProblemDetail (RFC 9457) with an additional {@code code} property.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ProblemDetails problemDetails;
    private final MessageSource messages;

    public ApiExceptionHandler(ProblemDetails problemDetails, MessageSource messages) {
        this.problemDetails = problemDetails;
        this.messages = messages;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ProblemDetail problem = problemDetails.of(ex.code());
        if (!ex.violations().isEmpty()) {
            problem.setProperty("errors", ex.violations().stream()
                    .map(violation -> Map.of(
                            "field", violation.field(),
                            "message", messages.getMessage(violation.messageKey(), null, LocaleContextHolder.getLocale())))
                    .toList());
        }
        return ResponseEntity.status(ex.code().status()).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError().body(problemDetails.of(ErrorCode.INTERNAL_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problemDetails.of(ErrorCode.VALIDATION_FAILED);
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                           HttpHeaders headers, HttpStatusCode status,
                                                                           WebRequest request) {
        ProblemDetail problem = problemDetails.of(ErrorCode.VALIDATION_FAILED);
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream().map(error -> Map.of(
                        "field", String.valueOf(result.getMethodParameter().getParameterName()),
                        "message", messages.getMessage(error, LocaleContextHolder.getLocale()))))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(problemDetails.of(ErrorCode.MALFORMED_REQUEST));
    }

    /**
     * Adds the {@code code} property to the ProblemDetail bodies created by Spring MVC itself (404, 405, ...).
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey(ProblemDetails.CODE_PROPERTY))) {
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            problem.setProperty(ProblemDetails.CODE_PROPERTY, status != null ? status.name() : String.valueOf(statusCode.value()));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private Map<String, String> toFieldError(FieldError error) {
        return Map.of(
                "field", error.getField(),
                "message", messages.getMessage(error, LocaleContextHolder.getLocale()));
    }
}
