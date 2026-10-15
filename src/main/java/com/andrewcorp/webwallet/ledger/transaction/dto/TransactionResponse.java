package com.andrewcorp.webwallet.ledger.transaction.dto;

import com.andrewcorp.webwallet.ledger.NamedResponse;
import com.andrewcorp.webwallet.ledger.tag.Tag;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionSource;
import com.andrewcorp.webwallet.ledger.transaction.TransactionSplit;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record TransactionResponse(
        Long id,
        TransactionType type,
        LocalDate date,
        Long accountId,
        BigDecimal amount,
        String currency,
        @Schema(nullable = true) Long toAccountId,
        @Schema(nullable = true) BigDecimal toAmount,
        @Schema(nullable = true) String toCurrency,
        @Schema(nullable = true) BigDecimal originalAmount,
        @Schema(nullable = true) String originalCurrency,
        @Schema(nullable = true) NamedResponse payee,
        @Schema(nullable = true) String comment,
        List<SplitResponse> splits,
        List<Long> tagIds,
        TransactionSource source,
        Instant createdAt) {

    public record SplitResponse(
            Long id,
            @Schema(nullable = true) Long categoryId,
            BigDecimal amount,
            @Schema(nullable = true) String comment) {

        static SplitResponse from(TransactionSplit split) {
            return new SplitResponse(
                    split.getId(),
                    split.getCategory() != null ? split.getCategory().getId() : null,
                    split.getAmount(),
                    split.getComment());
        }
    }

    public static TransactionResponse from(Transaction t) {
        boolean transfer = t.getToAccount() != null;
        return new TransactionResponse(
                t.getId(),
                t.getType(),
                t.getDate(),
                t.getAccount().getId(),
                t.getAmount(),
                t.getAccount().getCurrency(),
                transfer ? t.getToAccount().getId() : null,
                t.getToAmount(),
                transfer ? t.getToAccount().getCurrency() : null,
                t.getOriginalAmount(),
                t.getOriginalCurrency(),
                t.getPayee() != null
                        ? new NamedResponse(t.getPayee().getId(), t.getPayee().getName())
                        : null,
                t.getComment(),
                t.getSplits().stream().map(SplitResponse::from).toList(),
                t.getTags().stream().map(Tag::getId).sorted().toList(),
                t.getSource(),
                t.getCreatedAt());
    }
}
