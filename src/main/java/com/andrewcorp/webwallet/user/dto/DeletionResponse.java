package com.andrewcorp.webwallet.user.dto;

import java.time.Instant;

/** @param deletionScheduledAt when the account and its data are deleted, unless the user cancels before */
public record DeletionResponse(Instant deletionScheduledAt) {}
