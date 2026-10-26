package com.andrewcorp.webwallet.auth.dto;

/**
 * @param googleSignIn whether sign-in with Google is configured on this server
 * @param demo whether a demo sandbox can be opened without registration
 */
public record AuthOptionsResponse(boolean googleSignIn, boolean demo) {}
