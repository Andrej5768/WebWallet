package com.andrewcorp.webwallet.user.dto;

/**
 * @param secret Base32 key to type into the authenticator app by hand
 * @param otpauthUri the same as an {@code otpauth://} link, for a QR code
 */
public record TwoFactorSetupResponse(String secret, String otpauthUri) {}
