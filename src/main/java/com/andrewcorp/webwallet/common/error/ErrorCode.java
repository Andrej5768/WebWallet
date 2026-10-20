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
    /**
     * Signed in to an account that is to be deleted; the problem has restoreToken for /api/v1/auth/restore and
     * deletionScheduledAt.
     */
    ACCOUNT_DELETION_SCHEDULED(HttpStatus.FORBIDDEN, "error.accountDeletionScheduled"),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "error.tooManyAttempts"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "error.tooManyRequests"),
    INVALID_TOKEN(HttpStatus.BAD_REQUEST, "error.invalidToken"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "error.invalidRefreshToken"),
    INVALID_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "error.invalidCurrentPassword"),
    INVALID_TWO_FACTOR_CODE(HttpStatus.BAD_REQUEST, "error.invalidTwoFactorCode"),
    /** The time to enter the two-factor code is over; the client starts the sign-in again. */
    TWO_FACTOR_EXPIRED(HttpStatus.UNAUTHORIZED, "error.twoFactorExpired"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "error.unauthorized"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "error.forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "error.notFound"),
    RESOURCE_IN_USE(HttpStatus.CONFLICT, "error.resourceInUse"),
    CONFLICT(HttpStatus.CONFLICT, "error.conflict"),
    INVALID_EXTERNAL_TOKEN(HttpStatus.BAD_REQUEST, "error.import.token"),
    EXTERNAL_SERVICE_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "error.import.unavailable"),
    INVALID_IMPORT_FILE(HttpStatus.BAD_REQUEST, "error.import.file"),
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
