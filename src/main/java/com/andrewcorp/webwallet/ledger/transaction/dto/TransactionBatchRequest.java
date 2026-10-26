package com.andrewcorp.webwallet.ledger.transaction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** @param transactions up to 100 transactions, created together */
public record TransactionBatchRequest(
        @NotEmpty @Size(max = 100) List<@Valid TransactionRequest> transactions) {}
