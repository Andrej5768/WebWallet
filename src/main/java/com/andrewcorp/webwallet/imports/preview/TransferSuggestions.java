package com.andrewcorp.webwallet.imports.preview;

import com.andrewcorp.webwallet.imports.ImportOperation;
import com.andrewcorp.webwallet.imports.ImportOperation.TransferSuggestion;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.ledger.transaction.TransferPairs;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Finds the other half of a transfer for the expenses and incomes of an import preview: an operation of the same import
 * or a transaction already in the ledger, the same day and currency, on another account, within the tolerance of
 * {@link TransferPairs}. The user confirms the pairs; nothing is merged here.
 */
@Component
class TransferSuggestions {

    private static final int MAX_SUGGESTIONS = 5;

    private final TransferPairs pairs;

    TransferSuggestions(TransferPairs pairs) {
        this.pairs = pairs;
    }

    List<ImportOperation> add(Long userId, List<ImportOperation> operations) {
        List<ImportOperation> halves =
                operations.stream().filter(TransferSuggestions::half).toList();
        if (halves.isEmpty()) {
            return operations;
        }
        LocalDate from = halves.stream()
                .map(ImportOperation::date)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        LocalDate to = halves.stream()
                .map(ImportOperation::date)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        Map<String, List<TransferPairs.Candidate>> ledger = pairs.candidates(userId, from, to).stream()
                .collect(Collectors.groupingBy(candidate -> candidate.date() + "|" + candidate.currency()));
        Map<String, List<ImportOperation>> file =
                halves.stream().collect(Collectors.groupingBy(TransferSuggestions::day));
        return operations.stream()
                .map(operation ->
                        half(operation) ? operation.withSuggestions(suggest(operation, file, ledger)) : operation)
                .toList();
    }

    private static List<TransferSuggestion> suggest(
            ImportOperation operation,
            Map<String, List<ImportOperation>> file,
            Map<String, List<TransferPairs.Candidate>> ledger) {
        boolean outgoing = operation.type() == TransactionType.EXPENSE;
        List<TransferSuggestion> suggestions = new ArrayList<>();
        for (ImportOperation other : file.getOrDefault(day(operation), List.of())) {
            if (other.type() != operation.type() && !sameAccount(operation, other)) {
                suggestion(operation, outgoing, other.amount())
                        .ifPresent(difference -> suggestions.add(new TransferSuggestion(
                                other.key(),
                                null,
                                other.date(),
                                other.account().name(),
                                other.amount(),
                                other.currency(),
                                other.payee() != null ? other.payee() : other.comment(),
                                difference.signum() == 0,
                                difference)));
            }
        }
        for (TransferPairs.Candidate candidate : ledger.getOrDefault(day(operation), List.of())) {
            if (candidate.type() != operation.type()
                    && !Objects.equals(
                            candidate.accountId(), operation.account().id())) {
                suggestion(operation, outgoing, candidate.amount())
                        .ifPresent(difference -> suggestions.add(new TransferSuggestion(
                                null,
                                candidate.id(),
                                candidate.date(),
                                candidate.accountName(),
                                candidate.amount(),
                                candidate.currency(),
                                candidate.description(),
                                difference.signum() == 0,
                                difference)));
            }
        }
        suggestions.sort(Comparator.comparing(TransferSuggestion::exact)
                .reversed()
                .thenComparing(suggestion -> suggestion.difference().abs()));
        return suggestions.size() > MAX_SUGGESTIONS ? suggestions.subList(0, MAX_SUGGESTIONS) : suggestions;
    }

    /** The difference of the received amount from the sent one, when they are close enough. */
    private static Optional<BigDecimal> suggestion(
            ImportOperation operation, boolean outgoing, BigDecimal otherAmount) {
        BigDecimal sent = outgoing ? operation.amount() : otherAmount;
        BigDecimal received = outgoing ? otherAmount : operation.amount();
        return TransferPairs.close(sent, received)
                ? Optional.of(TransferPairs.difference(sent, received))
                : Optional.empty();
    }

    private static boolean half(ImportOperation operation) {
        return !operation.duplicate()
                && operation.transferLike()
                && (operation.type() == TransactionType.EXPENSE || operation.type() == TransactionType.INCOME);
    }

    private static boolean sameAccount(ImportOperation a, ImportOperation b) {
        return a.account().id() != null && b.account().id() != null
                ? a.account().id().equals(b.account().id())
                : a.account().name().equals(b.account().name());
    }

    private static String day(ImportOperation operation) {
        return operation.date() + "|" + operation.currency();
    }
}
