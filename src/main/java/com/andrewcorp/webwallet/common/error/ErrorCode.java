package com.andrewcorp.webwallet.common.error;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes returned in the {@code code} property of every error response.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "error.validation"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "error.malformedRequest"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "error.invalidCredentials"),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "error.emailNotVerified"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "error.accountDisabled"),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "error.tooManyAttempts"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "error.emailTaken"),
    INVALID_TOKEN(HttpStatus.BAD_REQUEST, "error.invalidToken"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "error.invalidRefreshToken"),
    INVALID_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "error.invalidCurrentPassword"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "error.unauthorized"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "error.forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "error.notFound"),
    RESOURCE_IN_USE(HttpStatus.CONFLICT, "error.resourceInUse"),
    RATE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "error.rateUnavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "error.internal");

    private final HttpStatus status;
    private final String messageKey;

    ErrorCode(HttpStatus status, String messageKey) {
        this.status = status;
        this.messageKey = messageKey;
    }

    public HttpStatus status() {
        return status;
    }

    public String messageKey() {
        return messageKey;
    }
}
