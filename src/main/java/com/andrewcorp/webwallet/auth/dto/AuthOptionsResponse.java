package com.andrewcorp.webwallet.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @param googleSignIn whether sign-in with Google is configured on this server
 * @param demo whether a demo sandbox can be opened without registration
 */
/**
 * @param googleWebClientId the OAuth web client the apps hand to Google as the server client; null while Google
 *     sign-in is off
 */
public record AuthOptionsResponse(
        boolean googleSignIn,
        boolean demo,
        @Schema(nullable = true) String googleWebClientId) {}
