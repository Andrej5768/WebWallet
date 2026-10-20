package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Transactions")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/transactions")
public class TransferPairController {

    private final TransferPairs pairs;
    private final TransactionService transactionService;

    public TransferPairController(TransferPairs pairs, TransactionService transactionService) {
        this.pairs = pairs;
        this.transactionService = transactionService;
    }

    /**
     * @param expenseId  the money leaving one account
     * @param incomeId   the money arriving on another
     * @param exact      the same amount on both sides
     * @param difference how much more (positive) or less (negative) arrived, as a share of the sent amount
     */
    public record TransferPairResponse(
            Long expenseId, Long incomeId, LocalDate date, boolean exact, BigDecimal difference) {}

    public record TransferPairRequest(
            @NotNull Long expenseId, @NotNull Long incomeId) {}

    @Operation(
            summary = "Expenses and incomes that may be one transfer",
            description = "The same day, different own accounts in the same currency, amounts within 10 %, both"
                    + " without a category or marked as transfers by the bank; dismissed pairs are left out")
    @GetMapping("/transfer-pairs")
    public List<TransferPairResponse> suggestions(
            @CurrentUserId Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return pairs.suggestions(userId, from, to).stream()
                .map(pair -> new TransferPairResponse(
                        pair.expense().getId(),
                        pair.income().getId(),
                        pair.expense().getDate(),
                        pair.exact(),
                        TransferPairs.difference(
                                pair.expense().getAmount(), pair.income().getAmount())))
                .toList();
    }

    @Operation(
            summary = "Merge an expense and an income into one transfer",
            description = "The expense becomes a transfer to the account of the income with both amounts; the income"
                    + " is deleted")
    @PostMapping("/transfer-pairs/merge")
    public TransactionResponse merge(@CurrentUserId Long userId, @Valid @RequestBody TransferPairRequest request) {
        return transactionService.get(
                userId,
                pairs.merge(userId, request.expenseId(), request.incomeId()).getId());
    }

    @Operation(summary = "Say an expense and an income are not one transfer, so that the pair is not suggested again")
    @PostMapping("/transfer-pairs/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@CurrentUserId Long userId, @Valid @RequestBody TransferPairRequest request) {
        pairs.dismiss(userId, request.expenseId(), request.incomeId());
    }

    @Operation(
            summary = "Split a transfer into an expense and an income",
            description = "Undoes a merge: the income is created again on the target account, and the pair is not"
                    + " suggested again. Returns the expense.")
    @PostMapping("/{id}/split-transfer")
    public TransactionResponse split(@CurrentUserId Long userId, @PathVariable Long id) {
        return transactionService.get(userId, pairs.split(userId, id).getId());
    }
}
