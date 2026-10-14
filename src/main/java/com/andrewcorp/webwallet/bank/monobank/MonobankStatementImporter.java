package com.andrewcorp.webwallet.bank.monobank;

import com.andrewcorp.webwallet.bank.monobank.MonobankClient.StatementItem;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.payee.PayeeService;
import com.andrewcorp.webwallet.ledger.rule.CategorySuggester;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.Transaction.SplitData;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionSource;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
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
    /** Money transfers; transfers between own cards come as a pair of such operations. */
    private static final short TRANSFER_MCC = 4829;

    /** Operation ids are used in queries and as external ids; anything else is not from Monobank. */
    static final Pattern ITEM_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private static final int MATCH_DAYS = 3;
    private static final int MAX_PAYEE_LENGTH = 200;
    private static final int MAX_COMMENT_LENGTH = 1000;

    private final TransactionRepository transactions;
    private final PayeeService payees;
    private final CategorySuggester categories;

    MonobankStatementImporter(TransactionRepository transactions, PayeeService payees, CategorySuggester categories) {
        this.transactions = transactions;
        this.payees = payees;
        this.categories = categories;
    }

    /**
     * @param account     account the operations belong to
     * @param ownAccounts the other synchronized accounts of the same bank, for transfers between own cards
     */
    Result importItems(Account account, Collection<Account> ownAccounts, List<StatementItem> items) {
        Result result = new Result();
        // Oldest first, so that the categories learned from earlier operations apply to later ones
        items.stream()
                .sorted(Comparator.comparingLong(StatementItem::time))
                .forEach(item -> importItem(account, ownAccounts, item, result));
        return result;
    }

    private void importItem(Account account, Collection<Account> ownAccounts, StatementItem item, Result result) {
        if (item.amount() == 0
                || item.id() == null
                || !ITEM_ID.matcher(item.id()).matches()) {
            return;
        }
        Long userId = account.getUserId();
        BigDecimal amount = Currencies.fromMinorUnits(Math.abs(item.amount()), account.getCurrency());
        TransactionType type = item.amount() > 0 ? TransactionType.INCOME : TransactionType.EXPENSE;
        LocalDate date = Instant.ofEpochSecond(item.time()).atZone(BANK_ZONE).toLocalDate();

        Optional<Transaction> imported = transactions
                .findByUserIdAndSourceAndExternalId(userId, TransactionSource.MONOBANK, item.id())
                .or(() -> transactions.findFirstByUserIdAndBankReferenceEndingWith(userId, item.id()));
        if (imported.isPresent()) {
            // A hold can change its amount when it is settled
            Transaction existing = imported.get();
            if (existing.getSource() == TransactionSource.MONOBANK
                    && existing.getType() == type
                    && existing.getAmount().compareTo(amount) != 0) {
                existing.setAmount(amount);
                existing.replaceSplits(existing.getSplits().stream()
                        .limit(1)
                        .map(split -> new SplitData(split.getCategory(), amount, split.getComment()))
                        .toList());
                result.updated++;
            }
            return;
        }

        Optional<Transaction> manual = transactions
                .findMatchCandidates(
                        userId,
                        account,
                        type,
                        amount,
                        date.minusDays(MATCH_DAYS),
                        date.plusDays(MATCH_DAYS),
                        TransactionSource.MONOBANK)
                .stream()
                .min(Comparator.comparingLong(
                        candidate -> Math.abs(ChronoUnit.DAYS.between(candidate.getDate(), date))));
        if (manual.isPresent()) {
            manual.get().setBankReference(item.id());
            manual.get().setMcc(mcc(item));
            result.matched++;
            return;
        }

        if (mergeTransfer(account, ownAccounts, item, type, amount, date)) {
            result.imported++;
            return;
        }

        Transaction transaction = new Transaction(userId);
        transaction.setSource(TransactionSource.MONOBANK);
        transaction.setExternalId(item.id());
        transaction.setType(type);
        transaction.setDate(date);
        transaction.setAccount(account);
        transaction.setAmount(amount);
        transaction.setMcc(mcc(item));
        transaction.setComment(truncate(item.comment(), MAX_COMMENT_LENGTH));
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
                List.of(new SplitData(categories.suggest(userId, payee, mcc(item), null, kind), amount, null)));
        transactions.save(transaction);
        result.imported++;
    }

    /**
     * A transfer between own cards comes as an expense on one card and an income on the other. When the other half
     * is already imported, it becomes a transfer instead of creating a second transaction.
     */
    private boolean mergeTransfer(
            Account account,
            Collection<Account> ownAccounts,
            StatementItem item,
            TransactionType type,
            BigDecimal amount,
            LocalDate date) {
        Short mcc = mcc(item);
        List<Account> others = ownAccounts.stream()
                .filter(other -> !other.getId().equals(account.getId()))
                .filter(other -> other.getCurrency().equals(account.getCurrency()))
                .toList();
        if (mcc == null || mcc != TRANSFER_MCC || others.isEmpty()) {
            return false;
        }
        TransactionType otherType = type == TransactionType.INCOME ? TransactionType.EXPENSE : TransactionType.INCOME;
        Optional<Transaction> counterpart = transactions
                .findTransferCounterparts(
                        account.getUserId(), TransactionSource.MONOBANK, otherType, amount, date, mcc, others)
                .stream()
                .findFirst();
        if (counterpart.isEmpty()) {
            return false;
        }
        Transaction transfer = counterpart.get();
        Account otherAccount = transfer.getAccount();
        transfer.setType(TransactionType.TRANSFER);
        transfer.replaceSplits(List.of());
        transfer.setPayee(null);
        if (type == TransactionType.INCOME) {
            // The counterpart is the outgoing half
            transfer.setToAccount(account);
            transfer.setToAmount(amount);
        } else {
            transfer.setAccount(account);
            transfer.setToAccount(otherAccount);
            transfer.setToAmount(transfer.getAmount());
            transfer.setAmount(amount);
        }
        // The id of this half is kept as the bank reference, so that it is recognized when it comes again
        transfer.setBankReference(item.id());
        return true;
    }

    private Payee payee(Long userId, StatementItem item) {
        String name =
                item.counterName() != null && !item.counterName().isBlank() ? item.counterName() : item.description();
        String trimmed = truncate(name, MAX_PAYEE_LENGTH);
        return trimmed == null ? null : payees.findOrCreate(userId, trimmed);
    }

    private static Short mcc(StatementItem item) {
        return item.mcc() == null ? null : item.mcc().shortValue();
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    static final class Result {
        int imported;
        int matched;
        int updated;
    }
}
