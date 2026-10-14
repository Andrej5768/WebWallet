package com.andrewcorp.webwallet.bank.monobank;

import com.andrewcorp.webwallet.bank.monobank.MonobankClient.StatementItem;
import com.andrewcorp.webwallet.common.util.Texts;
import com.andrewcorp.webwallet.common.util.Zones;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.payee.PayeeService;
import com.andrewcorp.webwallet.ledger.rule.CategorySuggester;
import com.andrewcorp.webwallet.ledger.transaction.OperationMatcher;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.Transaction.SplitData;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionSource;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns Monobank statement items into transactions of one account without creating duplicates of operations that
 * are already known: imported earlier, imported from Zen Money with the bank id, or entered manually.
 */
@Component
class MonobankStatementImporter {

    static final ZoneId BANK_ZONE = Zones.KYIV;
    /** Operation ids are used in queries and as external ids; anything else is not from Monobank. */
    static final Pattern ITEM_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private static final int MAX_PAYEE_LENGTH = 200;
    private static final int MAX_COMMENT_LENGTH = 1000;

    private final TransactionRepository transactions;
    private final OperationMatcher matcher;
    private final PayeeService payees;
    private final CategorySuggester categories;

    MonobankStatementImporter(
            TransactionRepository transactions,
            OperationMatcher matcher,
            PayeeService payees,
            CategorySuggester categories) {
        this.transactions = transactions;
        this.matcher = matcher;
        this.payees = payees;
        this.categories = categories;
    }

    /** @param account account the operations belong to */
    Result importItems(Account account, List<StatementItem> items) {
        if (items.isEmpty()) {
            // Most synchronizations find nothing new: the rules and payees are not even loaded
            return new Result();
        }
        Batch batch = new Batch(
                account, categories.session(account.getUserId()), payees.lookup(account.getUserId()), new Result());
        // Oldest first, so that the categories learned from earlier operations apply to later ones
        items.stream().sorted(Comparator.comparingLong(StatementItem::time)).forEach(item -> importItem(batch, item));
        return batch.result();
    }

    private record Batch(
            Account account, CategorySuggester.Session categories, PayeeService.Lookup payees, Result result) {}

    private void importItem(Batch batch, StatementItem item) {
        Account account = batch.account();
        Result result = batch.result();
        if (item.amount() == 0
                || item.id() == null
                || !ITEM_ID.matcher(item.id()).matches()) {
            return;
        }
        Long userId = account.getUserId();
        BigDecimal amount = Currencies.fromMinorUnits(Math.abs(item.amount()), account.getCurrency());
        TransactionType type = item.amount() > 0 ? TransactionType.INCOME : TransactionType.EXPENSE;
        LocalDate date = Instant.ofEpochSecond(item.time()).atZone(BANK_ZONE).toLocalDate();

        Optional<Transaction> known = matcher.findKnown(userId, item.id());
        if (known.isPresent()) {
            updateHold(known.get(), item, type, amount, result);
            return;
        }

        Optional<Transaction> manual = matcher.matchExisting(account, type, amount, date, item.id());
        if (manual.isPresent()) {
            if (manual.get().getType() != TransactionType.TRANSFER) {
                manual.get().setMcc(mcc(item));
            }
            result.matched++;
            return;
        }

        Short mcc = mcc(item);
        if (mcc != null
                && mcc == OperationMatcher.TRANSFER_MCC
                // Transfers between own Monobank cards are free: no fee tolerance
                && matcher.mergeTransfer(account, type, amount, date, item.id(), false)
                        .isPresent()) {
            result.imported++;
            return;
        }

        Transaction transaction = new Transaction(userId);
        transaction.setSource(TransactionSource.MONOBANK);
        transaction.setExternalId(item.id());
        transaction.setBankId(item.id());
        transaction.setHold(item.hold());
        transaction.setType(type);
        transaction.setDate(date);
        transaction.setAccount(account);
        transaction.setAmount(amount);
        transaction.setMcc(mcc);
        transaction.setComment(Texts.truncate(item.comment(), MAX_COMMENT_LENGTH));
        String operationCurrency = Currencies.byNumericCode(item.currencyCode());
        if (operationCurrency != null && !operationCurrency.equals(account.getCurrency())) {
            transaction.setOriginalAmount(
                    Currencies.fromMinorUnits(Math.abs(item.operationAmount()), operationCurrency));
            transaction.setOriginalCurrency(operationCurrency);
        }
        Payee payee = payee(batch.payees(), item);
        transaction.setPayee(payee);
        // Money back from a merchant is a refund: it goes to the expense category of the purchase
        boolean refund = type == TransactionType.INCOME && mcc != null && mcc != OperationMatcher.TRANSFER_MCC;
        CategoryKind kind = type == TransactionType.INCOME && !refund ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        transaction.replaceSplits(
                List.of(new SplitData(batch.categories().suggest(payee, mcc, null, kind), amount, null)));
        transactions.save(transaction);
        result.imported++;
    }

    /**
     * A hold can change its amount when it is settled. A transaction the user split into several categories keeps its
     * amount: there is no way to tell which part changed.
     */
    private static void updateHold(
            Transaction existing, StatementItem item, TransactionType type, BigDecimal amount, Result result) {
        if (existing.getSource() != TransactionSource.MONOBANK || existing.getType() != type) {
            return;
        }
        existing.setHold(existing.isHold() && item.hold());
        if (existing.getAmount().compareTo(amount) == 0 || existing.getSplits().size() > 1) {
            return;
        }
        existing.setAmount(amount);
        existing.replaceSplits(existing.getSplits().stream()
                .map(split -> new SplitData(split.getCategory(), amount, split.getComment()))
                .toList());
        result.updated++;
    }

    /**
     * Deletes the holds of the account that the bank no longer lists: the payment was cancelled. Only days fully
     * inside the statement period are checked.
     *
     * @param items the complete statement of the account for the period
     */
    void removeCancelledHolds(Account account, Instant from, Instant to, List<StatementItem> items) {
        Set<String> listed = items.stream().map(StatementItem::id).collect(Collectors.toSet());
        LocalDate firstFullDay = from.atZone(BANK_ZONE).toLocalDate().plusDays(1);
        LocalDate lastDay = to.atZone(BANK_ZONE).toLocalDate();
        transactions.findHolds(account, TransactionSource.MONOBANK, firstFullDay, lastDay).stream()
                .filter(hold -> !listed.contains(hold.getBankId()))
                .forEach(transactions::delete);
    }

    private static Payee payee(PayeeService.Lookup payees, StatementItem item) {
        String name =
                item.counterName() != null && !item.counterName().isBlank() ? item.counterName() : item.description();
        String trimmed = Texts.truncate(name, MAX_PAYEE_LENGTH);
        return trimmed == null ? null : payees.findOrCreate(trimmed);
    }

    private static Short mcc(StatementItem item) {
        return item.mcc() == null ? null : item.mcc().shortValue();
    }

    static final class Result {
        int imported;
        int matched;
        int updated;
    }
}
