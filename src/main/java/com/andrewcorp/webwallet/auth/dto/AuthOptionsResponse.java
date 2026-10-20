package com.andrewcorp.webwallet.auth.dto;

/** @param googleSignIn whether sign-in with Google is configured on this server */
public record AuthOptionsResponse(boolean googleSignIn) {}
