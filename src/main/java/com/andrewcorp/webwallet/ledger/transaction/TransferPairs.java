package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.ledger.transaction.Transaction.SplitData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pairs of an expense on one own account and an income on another that may be one transfer: the same day and amounts
 * within {@link #TOLERANCE} of each other (a bank fee, a rounding). They are only suggested; the user merges a pair
 * into a transfer, or says it is not one. Exact pairs that the bank marks as transfers are merged on import without
 * asking, see {@link OperationMatcher}.
 */
@Service
public class TransferPairs {

    /** The received amount may differ from the sent one by up to this share, e.g. fees of transfers between banks. */
    static final BigDecimal TOLERANCE = new BigDecimal("0.10");

    private final TransactionRepository transactions;
    private final TransferDismissalRepository dismissals;
    private final TransactionService transactionService;

    public TransferPairs(
            TransactionRepository transactions,
            TransferDismissalRepository dismissals,
            TransactionService transactionService) {
        this.transactions = transactions;
        this.dismissals = dismissals;
        this.transactionService = transactionService;
    }

    /** An expense and an income that may be the two halves of one transfer. */
    public record Pair(Transaction expense, Transaction income) {

        public boolean exact() {
            return expense.getAmount().compareTo(income.getAmount()) == 0;
        }
    }

    /** Whether the received amount is within the tolerance of the sent one. */
    public static boolean close(BigDecimal sent, BigDecimal received) {
        BigDecimal larger = sent.max(received);
        return larger.signum() > 0 && sent.subtract(received).abs().compareTo(larger.multiply(TOLERANCE)) <= 0;
    }

    /** How much more (positive) or less (negative) arrived than was sent, as a share of the sent amount. */
    public static BigDecimal difference(BigDecimal sent, BigDecimal received) {
        return received.subtract(sent).divide(sent, 4, RoundingMode.HALF_UP);
    }

    /**
     * Whether the operation may be a half of a transfer: without a category, or marked as a transfer by the bank. A
     * categorized purchase or salary is not suggested, which keeps the suggestions few.
     */
    public static boolean transferLike(Transaction transaction) {
        if (transaction.getMcc() != null && transaction.getMcc() == OperationMatcher.TRANSFER_MCC) {
            return true;
        }
        if (transaction.getBankCategory() != null
                && OperationMatcher.TRANSFER_CATEGORIES.contains(transaction.getBankCategory())) {
            return true;
        }
        return transaction.getSplits().stream().allMatch(split -> split.getCategory() == null);
    }

    /** A transaction of the ledger that may be a half of a transfer, as an import preview shows it. */
    public record Candidate(
            Long id,
            TransactionType type,
            LocalDate date,
            Long accountId,
            String accountName,
            String currency,
            BigDecimal amount,
            String description) {}

    /** Expenses and incomes of the dates that may be halves of transfers. */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(Long userId, LocalDate from, LocalDate to) {
        return transactions.findPairCandidates(userId, from, to).stream()
                .filter(TransferPairs::transferLike)
                .map(t -> new Candidate(
                        t.getId(),
                        t.getType(),
                        t.getDate(),
                        t.getAccount().getId(),
                        t.getAccount().getName(),
                        t.getAccount().getCurrency(),
                        t.getAmount(),
                        t.getPayee() != null ? t.getPayee().getName() : t.getComment()))
                .toList();
    }

    /** Suggested pairs of the period, newest first, without the ones the user dismissed. */
    @Transactional(readOnly = true)
    public List<Pair> suggestions(Long userId, LocalDate from, LocalDate to) {
        Set<String> dismissed = dismissals.findByUserId(userId).stream()
                .map(dismissal -> dismissal.getExpenseId() + ":" + dismissal.getIncomeId())
                .collect(Collectors.toSet());
        Map<String, List<Transaction>> byDay = transactions.findPairCandidates(userId, from, to).stream()
                .filter(TransferPairs::transferLike)
                .collect(Collectors.groupingBy(
                        t -> t.getDate() + "|" + t.getAccount().getCurrency()));
        List<Pair> pairs = new ArrayList<>();
        for (List<Transaction> day : byDay.values()) {
            for (Transaction expense : day) {
                if (expense.getType() != TransactionType.EXPENSE) {
                    continue;
                }
                for (Transaction income : day) {
                    if (income.getType() == TransactionType.INCOME
                            && !income.getAccount()
                                    .getId()
                                    .equals(expense.getAccount().getId())
                            && close(expense.getAmount(), income.getAmount())
                            && !dismissed.contains(expense.getId() + ":" + income.getId())) {
                        pairs.add(new Pair(expense, income));
                    }
                }
            }
        }
        pairs.sort(Comparator.comparing((Pair pair) -> pair.expense().getDate())
                .reversed()
                .thenComparing(pair -> pair.expense().getId()));
        return pairs;
    }

    /** Merges the expense and the income of the user into one transfer. */
    @Transactional
    public Transaction merge(Long userId, Long expenseId, Long incomeId) {
        Transaction expense = find(userId, expenseId);
        Transaction income = find(userId, incomeId);
        return merge(userId, expense, income);
    }

    /**
     * Makes the expense a transfer to the account of the income, with both amounts as they were, and deletes the
     * income. Its bank and import ids move to the incoming half, so that it is not imported again.
     */
    @Transactional
    public Transaction merge(Long userId, Transaction expense, Transaction income) {
        if (expense.getType() != TransactionType.EXPENSE
                || income.getType() != TransactionType.INCOME
                || expense.getAccount().getId().equals(income.getAccount().getId())) {
            throw ApiException.invalid("incomeId", "validation.transferPair");
        }
        // The income goes first: its lookup flushes, and the cleared splits of the expense must be flushed only once
        transactionService.delete(userId, income.getId());
        expense.setType(TransactionType.TRANSFER);
        expense.setHold(false);
        expense.setToAccount(income.getAccount());
        expense.setToAmount(income.getAmount());
        expense.setToBankId(income.getBankId());
        expense.setToExternalId(income.getExternalId());
        expense.setOriginalAmount(null);
        expense.setOriginalCurrency(null);
        expense.replaceSplits(List.of());
        expense.setPayee(null);
        if (expense.getComment() == null) {
            expense.setComment(income.getComment());
        }
        return transactions.save(expense);
    }

    /** Splits a transfer back into an expense and an income, and does not suggest the pair again. */
    @Transactional
    public Transaction split(Long userId, Long transferId) {
        Transaction transfer = find(userId, transferId);
        if (transfer.getType() != TransactionType.TRANSFER) {
            throw ApiException.invalid("id", "validation.transferPair");
        }
        Transaction income = new Transaction(userId);
        income.setType(TransactionType.INCOME);
        income.setDate(transfer.getDate());
        income.setAccount(transfer.getToAccount());
        income.setAmount(transfer.getToAmount());
        income.setBankId(transfer.getToBankId());
        income.setExternalId(transfer.getToExternalId());
        income.setSource(transfer.getSource());
        income.setComment(transfer.getComment());
        income.replaceSplits(List.of(new SplitData(null, transfer.getToAmount(), null)));
        transactions.save(income);

        transfer.setType(TransactionType.EXPENSE);
        transfer.setToAccount(null);
        transfer.setToAmount(null);
        transfer.setToBankId(null);
        transfer.setToExternalId(null);
        transfer.replaceSplits(List.of(new SplitData(null, transfer.getAmount(), null)));
        transactions.saveAndFlush(transfer);
        dismissals.save(new TransferDismissal(userId, transfer.getId(), income.getId()));
        return transfer;
    }

    /** Remembers that the expense and the income are not one transfer. */
    @Transactional
    public void dismiss(Long userId, Long expenseId, Long incomeId) {
        find(userId, expenseId);
        find(userId, incomeId);
        if (!dismissals.existsByExpenseIdAndIncomeId(expenseId, incomeId)) {
            dismissals.save(new TransferDismissal(userId, expenseId, incomeId));
        }
    }

    private Transaction find(Long userId, Long id) {
        return transactions.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }
}
