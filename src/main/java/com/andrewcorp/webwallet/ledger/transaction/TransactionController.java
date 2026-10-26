package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.common.web.PageResponse;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionBatchRequest;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionFilter;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionRequest;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

@Tag(name = "Transactions")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("date"), Sort.Order.desc("id"));

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @Operation(
            summary = "List transactions, newest first",
            description = "All given filters must match. q searches the payee name, the comment and the comments of"
                    + " splits, ignoring case; when it is a number (1284.50, 1284,50 or 1 284,50), transactions with"
                    + " this amount match too (the amount or, of a transfer, the received amount).")
    @GetMapping
    public PageResponse<TransactionResponse> list(
            @CurrentUserId Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) Long payeeId,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        TransactionFilter filter = new TransactionFilter(from, to, accountId, categoryId, type, payeeId, tagId, q);
        return PageResponse.of(
                transactionService.list(userId, filter, PageRequest.of(page, size, NEWEST_FIRST)), t -> t);
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@CurrentUserId Long userId, @PathVariable Long id) {
        return transactionService.get(userId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse create(@CurrentUserId Long userId, @Valid @RequestBody TransactionRequest request) {
        return transactionService.create(userId, request);
    }

    @Operation(
            summary = "Create several transactions at once",
            description = "All of them are created, or none when one is invalid: the field of a violation is then"
                    + " transactions[i].field.")
    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public List<TransactionResponse> createBatch(
            @CurrentUserId Long userId, @Valid @RequestBody TransactionBatchRequest request) {
        return transactionService.createAll(userId, request.transactions());
    }

    @PutMapping("/{id}")
    public TransactionResponse update(
            @CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody TransactionRequest request) {
        return transactionService.update(userId, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId Long userId, @PathVariable Long id) {
        transactionService.delete(userId, id);
    }
}
