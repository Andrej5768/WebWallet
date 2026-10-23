package com.andrewcorp.webwallet.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * Native apps send the refresh token here; browsers send the cookie and no body.
 *
 * @param refreshToken the token of login or the last refresh, null when the cookie carries it
 */
public record RefreshTokenRequest(@Size(max = 128) String refreshToken) {

    /** The token must never end up in a log. */
    @Override
    public String toString() {
        return "RefreshTokenRequest[***]";
    }
}
