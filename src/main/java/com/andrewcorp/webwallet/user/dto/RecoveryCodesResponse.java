package com.andrewcorp.webwallet.user.dto;

import java.util.List;

/** @param recoveryCodes one-time codes, shown only now; each signs in once without the authenticator app */
public record RecoveryCodesResponse(List<String> recoveryCodes) {}
