package com.andrewcorp.webwallet.planning;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionResponse;
import com.andrewcorp.webwallet.planning.RecurringDtos.ConfirmRequest;
import com.andrewcorp.webwallet.planning.RecurringDtos.LinkRequest;
import com.andrewcorp.webwallet.planning.RecurringDtos.OccurrenceResponse;
import com.andrewcorp.webwallet.planning.RecurringDtos.RecurringRequest;
import com.andrewcorp.webwallet.planning.RecurringDtos.RecurringResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Recurring payments")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/recurring")
public class RecurringController {

    private static final int MAX_DAYS = 400;

    private final RecurringService recurringService;
    private final Clock clock;

    public RecurringController(RecurringService recurringService, Clock clock) {
        this.recurringService = recurringService;
        this.clock = clock;
    }

    @Operation(summary = "List recurring payments by name, with the next date of each")
    @GetMapping
    public List<RecurringResponse> list(@CurrentUserId Long userId) {
        return recurringService.list(userId);
    }

    @GetMapping("/{id}")
    public RecurringResponse get(@CurrentUserId Long userId, @PathVariable Long id) {
        return recurringService.get(userId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringResponse create(@CurrentUserId Long userId, @Valid @RequestBody RecurringRequest request) {
        return recurringService.create(userId, request);
    }

    @Operation(summary = "Replace a recurring payment", description = "Occurrences already paid or skipped stay.")
    @PutMapping("/{id}")
    public RecurringResponse update(
            @CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody RecurringRequest request) {
        return recurringService.update(userId, id, request);
    }

    @Operation(summary = "Delete a recurring payment; the transactions that paid it stay")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId Long userId, @PathVariable Long id) {
        recurringService.delete(userId, id);
    }

    @Operation(
            summary = "Occurrences of all recurring payments in the period, by date",
            description = "By default from " + RecurringService.LOOKBACK_DAYS + " days ago to 31 days ahead, at most "
                    + MAX_DAYS + " days. Pending occurrences overdue for more than " + RecurringService.LOOKBACK_DAYS
                    + " days are left out. Due occurrences are matched with transactions first.")
    @GetMapping("/occurrences")
    public List<OccurrenceResponse> occurrences(
            @CurrentUserId Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate start = from != null ? from : today.minusDays(RecurringService.LOOKBACK_DAYS);
        LocalDate end = to != null ? to : today.plusDays(31);
        if (start.isAfter(end)) {
            throw ApiException.invalid("from", "error.report.period");
        }
        if (start.plusDays(MAX_DAYS).isBefore(end)) {
            throw ApiException.invalid("to", "error.recurring.periodTooLong");
        }
        return recurringService.occurrences(userId, start, end);
    }

    @Operation(
            summary = "Create the transaction of an occurrence and mark it as paid",
            description = "The body is optional; an occurrence already paid is rejected.")
    @PostMapping("/{id}/occurrences/{date}/confirm")
    public OccurrenceResponse confirm(
            @CurrentUserId Long userId,
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody(required = false) ConfirmRequest request) {
        return recurringService.confirm(
                userId, id, date, request != null ? request : new ConfirmRequest(null, null, null));
    }

    @Operation(
            summary = "Mark an occurrence as paid by an existing transaction",
            description = "The transaction must not pay another occurrence.")
    @PostMapping("/{id}/occurrences/{date}/link")
    public OccurrenceResponse link(
            @CurrentUserId Long userId,
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody LinkRequest request) {
        return recurringService.link(userId, id, date, request.transactionId());
    }

    @Operation(summary = "Skip an occurrence")
    @PostMapping("/{id}/occurrences/{date}/skip")
    public OccurrenceResponse skip(
            @CurrentUserId Long userId,
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return recurringService.skip(userId, id, date);
    }

    @Operation(
            summary = "Make an occurrence pending again",
            description = "Unlinks the transaction (which stays) or undoes the skip. The occurrence is then no longer"
                    + " matched or created automatically.")
    @DeleteMapping("/{id}/occurrences/{date}")
    public OccurrenceResponse reset(
            @CurrentUserId Long userId,
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return recurringService.reset(userId, id, date);
    }

    @Operation(
            summary = "Transactions that could pay an occurrence, best first",
            description = "Of the same type, within 10 days of the date and with up to half the amount off, not paying"
                    + " another occurrence; those on the account of the recurring payment first.")
    @GetMapping("/{id}/occurrences/{date}/candidates")
    public List<TransactionResponse> candidates(
            @CurrentUserId Long userId,
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return recurringService.candidates(userId, id, date);
    }
}
