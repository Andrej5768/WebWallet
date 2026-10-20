package com.andrewcorp.webwallet.common.error;

import java.util.List;
import java.util.Map;

/**
 * Business error that is rendered as a ProblemDetail response with the given code.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldViolation> violations;
    private final Map<String, Object> properties;

    public ApiException(ErrorCode code) {
        this(code, List.of(), Map.of());
    }

    /** An error whose problem details carry the extra properties, e.g. what the client needs to continue. */
    public ApiException(ErrorCode code, Map<String, Object> properties) {
        this(code, List.of(), properties);
    }

    private ApiException(ErrorCode code, List<FieldViolation> violations, Map<String, Object> properties) {
        super(code.name());
        this.code = code;
        this.violations = violations;
        this.properties = properties;
    }

    public static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND);
    }

    /** Validation error of a business rule, rendered like a bean validation error of the given field. */
    public static ApiException invalid(String field, String messageKey, Object... args) {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED, List.of(new FieldViolation(field, messageKey, args)), Map.of());
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldViolation> violations() {
        return violations;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    /**
     * @param messageKey key in messages.properties
     * @param args       arguments of the message, e.g. a limit
     */
    public record FieldViolation(String field, String messageKey, Object[] args) {

        public FieldViolation(String field, String messageKey) {
            this(field, messageKey, new Object[0]);
        }
    }
}
