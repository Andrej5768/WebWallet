package com.andrewcorp.webwallet.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param ticket from the fragment of the page the Google callback redirected to */
public record GoogleTicketRequest(@NotBlank @Size(max = 128) String ticket) {}
