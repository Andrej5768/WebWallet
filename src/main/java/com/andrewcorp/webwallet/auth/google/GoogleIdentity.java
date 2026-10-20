package com.andrewcorp.webwallet.auth.google;

/**
 * Claims of a verified Google ID token.
 *
 * @param subject the stable id of the Google account
 * @param emailVerified whether Google confirmed that the account owns the address
 * @param familyName null when the account has none
 */
public record GoogleIdentity(
        String subject, String email, boolean emailVerified, String givenName, String familyName) {}
