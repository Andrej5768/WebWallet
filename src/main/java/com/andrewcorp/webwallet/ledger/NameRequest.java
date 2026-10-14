package com.andrewcorp.webwallet.ledger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for creating or renaming a tag or a payee. */
public record NameRequest(@NotBlank @Size(max = 100) String name) {}
