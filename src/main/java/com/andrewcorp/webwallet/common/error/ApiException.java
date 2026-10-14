package com.andrewcorp.webwallet.common.error;

import java.util.List;

/**
 * Business error that is rendered as a ProblemDetail response with the given code.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldViolation> violations;

    public ApiException(ErrorCode code) {
        this(code, List.of());
    }

    private ApiException(ErrorCode code, List<FieldViolation> violations) {
        super(code.name());
        this.code = code;
        this.violations = violations;
    }

    public static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND);
    }

    /** Validation error of a business rule, rendered like a bean validation error of the given field. */
    public static ApiException invalid(String field, String messageKey) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, List.of(new FieldViolation(field, messageKey)));
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldViolation> violations() {
        return violations;
    }

    /**
     * @param messageKey key in messages.properties
     */
    public record FieldViolation(String field, String messageKey) {
    }
}
