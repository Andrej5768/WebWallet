package com.andrewcorp.webwallet.bank.monobank.dto;

import com.andrewcorp.webwallet.bank.SyncMode;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class MonobankDtos {

    private MonobankDtos() {}

    /**
     * The schedule fields are optional: a new connection gets INTERVAL from 08:00 to 23:00 and 06:00 for the daily
     * time, a reconnect keeps its schedule.
     *
     * @param token personal token from https://api.monobank.ua/
     * @param since date to import the history from; 31 days ago by default
     */
    public record ConnectRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String token,

            @PastOrPresent LocalDate since,
            SyncMode syncMode,

            @Schema(type = "string", format = "time", example = "08:00")
            LocalTime windowStart,

            @Schema(type = "string", format = "time", example = "23:00")
            LocalTime windowEnd,

            @Schema(type = "string", format = "time", example = "06:00")
            LocalTime dailyAt) {}

    /**
     * Times are local times of Kyiv, to the minute; omitted ones are kept. The window must start before it ends.
     *
     * @param windowStart first synchronization of the day in the INTERVAL mode
     * @param windowEnd   last synchronization of the day in the INTERVAL mode
     * @param dailyAt     time of the synchronization in the DAILY and WEBHOOK modes
     */
    public record ScheduleRequest(
            @NotNull SyncMode syncMode,

            @Schema(type = "string", format = "time", example = "08:00")
            LocalTime windowStart,

            @Schema(type = "string", format = "time", example = "23:00")
            LocalTime windowEnd,

            @Schema(type = "string", format = "time", example = "06:00")
            LocalTime dailyAt) {}

    /** @param accountId WebWallet account to import into, or null to stop synchronizing the bank account */
    public record LinkRequest(Long accountId) {}

    /**
     * @param lastError  error of the last request to the bank, if it failed
     * @param webhook    whether the webhook is registered at the bank, so that new operations arrive instantly
     * @param syncMode   INTERVAL, DAILY or WEBHOOK
     * @param nextSyncAt when the accounts are requested next; in the past while a synchronization is in progress
     *                   (e.g. the history is being loaded)
     */
    public record ConnectionResponse(
            String clientName,
            Instant connectedAt,
            Instant lastRequestAt,
            String lastError,
            boolean webhook,
            SyncMode syncMode,

            @JsonFormat(pattern = "HH:mm") @Schema(type = "string", format = "time", example = "08:00")
            LocalTime windowStart,

            @JsonFormat(pattern = "HH:mm") @Schema(type = "string", format = "time", example = "23:00")
            LocalTime windowEnd,

            @JsonFormat(pattern = "HH:mm") @Schema(type = "string", format = "time", example = "06:00")
            LocalTime dailyAt,

            Instant nextSyncAt,
            List<LinkResponse> accounts) {}

    /**
     * @param syncedUntil operations up to this moment are imported
     * @param bankBalance balance reported by the bank
     * @param balance     balance of the WebWallet account
     * @param difference  bank balance minus balance: positive when incomes are missing (or expenses were entered
     *                    twice), negative when expenses are missing, zero when the account is in sync
     */
    public record LinkResponse(
            Long id,
            String title,
            String currency,
            Long accountId,
            String accountName,
            Instant syncedUntil,
            BigDecimal bankBalance,
            BigDecimal balance,
            BigDecimal difference) {}
}
