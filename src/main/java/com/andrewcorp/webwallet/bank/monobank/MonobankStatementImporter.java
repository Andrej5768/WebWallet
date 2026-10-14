package com.andrewcorp.webwallet.bank.monobank;

import com.andrewcorp.webwallet.bank.monobank.MonobankClient.StatementItem;
import com.andrewcorp.webwallet.common.util.Texts;
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
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns Monobank statement items into transactions of one account without creating duplicates of operations that
 * are already known: imported earlier, imported from Zen Money with the bank id, or entered manually.
 */
@Component
class MonobankStatementImporter {

    static final ZoneId BANK_ZONE = ZoneId.of("Europe/Kyiv");
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
        Result result = new Result();
        // Oldest first, so that the categories learned from earlier operations apply to later ones
        items.stream()
                .sorted(Comparator.comparingLong(StatementItem::time))
                .forEach(item -> importItem(account, item, result));
        return result;
    }

    private void importItem(Account account, StatementItem item, Result result) {
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
            updateHold(known.get(), type, amount, result);
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
                && matcher.mergeTransfer(account, type, amount, date, item.id()).isPresent()) {
            result.imported++;
            return;
        }

        Transaction transaction = new Transaction(userId);
        transaction.setSource(TransactionSource.MONOBANK);
        transaction.setExternalId(item.id());
        transaction.setBankId(item.id());
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
        Payee payee = payee(userId, item);
        transaction.setPayee(payee);
        CategoryKind kind = type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        transaction.replaceSplits(
                List.of(new SplitData(categories.suggest(userId, payee, mcc, null, kind), amount, null)));
        transactions.save(transaction);
        result.imported++;
    }

    /**
     * A hold can change its amount when it is settled. A transaction the user split into several categories is left
     * as it is: there is no way to tell which part changed.
     */
    private static void updateHold(Transaction existing, TransactionType type, BigDecimal amount, Result result) {
        if (existing.getSource() != TransactionSource.MONOBANK
                || existing.getType() != type
                || existing.getAmount().compareTo(amount) == 0
                || existing.getSplits().size() > 1) {
            return;
        }
        existing.setAmount(amount);
        existing.replaceSplits(existing.getSplits().stream()
                .map(split -> new SplitData(split.getCategory(), amount, split.getComment()))
                .toList());
        result.updated++;
    }

    private Payee payee(Long userId, StatementItem item) {
        String name =
                item.counterName() != null && !item.counterName().isBlank() ? item.counterName() : item.description();
        String trimmed = Texts.truncate(name, MAX_PAYEE_LENGTH);
        return trimmed == null ? null : payees.findOrCreate(userId, trimmed);
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
