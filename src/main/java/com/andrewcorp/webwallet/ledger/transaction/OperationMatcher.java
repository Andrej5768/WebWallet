package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.ledger.account.Account;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Recognizes bank operations that are already in the ledger, for every importer (Monobank, PrivatBank, Zen Money):
 * by the bank id, as a transaction entered by hand, or as the other half of a transfer between own accounts.
 */
@Component
public class OperationMatcher {

    /** Money transfers; a transfer between own cards comes as a pair of such operations. */
    public static final short TRANSFER_MCC = 4829;
    /** Bank categories of transfers, e.g. in PrivatBank statements. */
    public static final Set<String> TRANSFER_CATEGORIES =
            Set.of("Перекази", "Зарахування переказу", "Зарахування зі своєї картки");

    /** A bank date may differ from the one entered by hand by a few days. */
    private static final int MATCH_DAYS = 3;
    /** Transfers between own cards lose up to ~4% in fees, e.g. 10 300 leaves one card and 10 000 arrives. */
    private static final BigDecimal MIN_TRANSFER_RATIO = new BigDecimal("0.96");

    private static final int MAX_BANK_ID_LENGTH = 200;
    private static final Pattern BANK_ID_PREFIX = Pattern.compile("^(?:\\[[^]]*])+-?");

    private final TransactionRepository transactions;

    public OperationMatcher(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    /** The id without the prefixes Zen Money adds, e.g. {@code [monobank][reg]-abc} to {@code abc}; null if blank. */
    public static String normalizeBankId(String bankId) {
        if (bankId == null) {
            return null;
        }
        String id = BANK_ID_PREFIX.matcher(bankId.trim()).replaceFirst("");
        if (id.isEmpty()) {
            return null;
        }
        return id.length() <= MAX_BANK_ID_LENGTH ? id : id.substring(0, MAX_BANK_ID_LENGTH);
    }

    /** The transaction that already stands for the bank operation. */
    public Optional<Transaction> findKnown(Long userId, String bankId) {
        return bankId == null
                ? Optional.empty()
                : transactions.findByBankId(userId, bankId).stream().findFirst();
    }

    /**
     * Links the operation to a transaction on the account not yet matched to the bank: an expense or income of the
     * same amount, or the matching half of a transfer, closest by date.
     *
     * @param type {@link TransactionType#EXPENSE} for money leaving the account, {@link TransactionType#INCOME} for
     *     money arriving
     */
    public Optional<Transaction> matchExisting(
            Account account, TransactionType type, BigDecimal amount, LocalDate date, String bankId) {
        Long userId = account.getUserId();
        LocalDate from = date.minusDays(MATCH_DAYS);
        LocalDate to = date.plusDays(MATCH_DAYS);
        List<Transaction> candidates =
                new ArrayList<>(transactions.findMatchCandidates(userId, account, type, amount, from, to));
        candidates.addAll(
                type == TransactionType.EXPENSE
                        ? transactions.findOutgoingTransferMatches(userId, account, amount, from, to)
                        : transactions.findIncomingTransferMatches(userId, account, amount, from, to));
        Optional<Transaction> match = closest(candidates, date);
        match.ifPresent(transaction -> {
            boolean incomingHalf = transaction.getType() == TransactionType.TRANSFER && type == TransactionType.INCOME;
            if (incomingHalf) {
                transaction.setToBankId(bankId);
            } else {
                transaction.setBankId(bankId);
            }
        });
        return match;
    }

    /**
     * Makes one transfer of the operation and its other half on another own account of the same currency, in
     * whatever order the two halves are imported. The amounts may differ by a fee: an exact amount is preferred, a
     * different one is accepted only when it is the only candidate.
     *
     * @param type {@link TransactionType#EXPENSE} for money leaving the account, {@link TransactionType#INCOME} for
     *     money arriving
     * @return the transfer, or empty when the other half is not there (yet)
     */
    public Optional<Transaction> mergeTransfer(
            Account account, TransactionType type, BigDecimal amount, LocalDate date, String bankId) {
        boolean outgoing = type == TransactionType.EXPENSE;
        BigDecimal min = outgoing ? amount.multiply(MIN_TRANSFER_RATIO) : amount;
        BigDecimal max = outgoing ? amount : amount.divide(MIN_TRANSFER_RATIO, 4, RoundingMode.HALF_UP);
        List<Transaction> candidates = transactions.findTransferCandidates(
                account.getUserId(),
                outgoing ? TransactionType.INCOME : TransactionType.EXPENSE,
                account,
                account.getCurrency(),
                date.minusDays(1),
                date.plusDays(1),
                min,
                max,
                TRANSFER_MCC,
                TRANSFER_CATEGORIES);
        List<Transaction> exact = candidates.stream()
                .filter(candidate -> candidate.getAmount().compareTo(amount) == 0)
                .toList();
        Optional<Transaction> counterpart = !exact.isEmpty()
                ? closest(exact, date)
                : candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
        counterpart.ifPresent(transfer -> {
            Account other = transfer.getAccount();
            BigDecimal otherAmount = transfer.getAmount();
            String otherBankId = transfer.getBankId();
            transfer.setType(TransactionType.TRANSFER);
            transfer.replaceSplits(List.of());
            transfer.setPayee(null);
            if (outgoing) {
                transfer.setAccount(account);
                transfer.setAmount(amount);
                transfer.setBankId(bankId);
                transfer.setToAccount(other);
                transfer.setToAmount(otherAmount);
                transfer.setToBankId(otherBankId);
            } else {
                transfer.setToAccount(account);
                transfer.setToAmount(amount);
                transfer.setToBankId(bankId);
            }
        });
        return counterpart;
    }

    private static Optional<Transaction> closest(List<Transaction> candidates, LocalDate date) {
        return candidates.stream()
                .min(Comparator.<Transaction>comparingLong(
                                candidate -> Math.abs(ChronoUnit.DAYS.between(candidate.getDate(), date)))
                        .thenComparing(Transaction::getId));
    }
}
