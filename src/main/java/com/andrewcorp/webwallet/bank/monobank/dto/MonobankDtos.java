package com.andrewcorp.webwallet.bank.monobank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class MonobankDtos {

    private MonobankDtos() {}

    /**
     * @param token personal token from https://api.monobank.ua/
     * @param since date to import the history from; 31 days ago by default
     */
    public record ConnectRequest(
            @NotBlank String token, @PastOrPresent LocalDate since) {}

    /** @param accountId WebWallet account to import into, or null to stop synchronizing the bank account */
    public record LinkRequest(Long accountId) {}

    /**
     * @param lastError error of the last request to the bank, if it failed
     * @param webhook   whether new operations arrive instantly; otherwise they are polled every few minutes
     */
    public record ConnectionResponse(
            String clientName,
            Instant connectedAt,
            Instant lastRequestAt,
            String lastError,
            boolean webhook,
            List<LinkResponse> accounts) {}

    /**
     * @param syncedUntil operations up to this moment are imported
     * @param bankBalance balance reported by the bank
     * @param balance     balance of the WebWallet account
     */
    public record LinkResponse(
            Long id,
            String title,
            String currency,
            Long accountId,
            String accountName,
            Instant syncedUntil,
            BigDecimal bankBalance,
            BigDecimal balance) {}
}
