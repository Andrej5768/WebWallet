package com.andrewcorp.webwallet.common.error;

/**
 * Business error that is rendered as a ProblemDetail response with the given code.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        super(code.name());
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
