package com.andrewcorp.webwallet.imports.monobank;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.util.Texts;
import com.andrewcorp.webwallet.imports.ImportPreviewCollector;
import com.andrewcorp.webwallet.imports.ImportResult;
import com.andrewcorp.webwallet.imports.ImportReview;
import com.andrewcorp.webwallet.ledger.LedgerLookups;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountService;
import com.andrewcorp.webwallet.ledger.account.AccountType;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports the operations of a Monobank PDF statement into an account. The card of a Monobank connection usually
 * has these operations already, brought in through the API with ids of their own; they are recognized by the day
 * and the amount, so that a statement over a synchronized period adds only what is missing.
 */
@Service
public class MonobankPdfImporter {

    /** Prefix of the statement keys, so that they never collide with the operation ids of the API. */
    static final String KEY_PREFIX = "monopdf";

    private final AccountRepository accounts;
    private final LedgerLookups lookups;
    private final AccountService accountService;
    private final TransactionRepository transactions;
    private final OperationMatcher matcher;
    private final PayeeService payees;
    private final CategorySuggester categories;

    public MonobankPdfImporter(
            AccountRepository accounts,
            LedgerLookups lookups,
            AccountService accountService,
            TransactionRepository transactions,
            OperationMatcher matcher,
            PayeeService payees,
            CategorySuggester categories) {
        this.accounts = accounts;
        this.lookups = lookups;
        this.accountService = accountService;
        this.transactions = transactions;
        this.matcher = matcher;
        this.payees = payees;
        this.categories = categories;
    }

    /**
     * @param accountId account to import into; when null, the account with the card number of the statement is used,
     *                  or a new one is created
     * @param review    changes of the user to the operations, keyed by {@link #key}
     * @param preview   records what is done with each operation, or null
     */
    @Transactional
    public ImportResult importStatement(
            Long userId,
            Long accountId,
            MonobankPdfStatement statement,
            ImportReview review,
            ImportPreviewCollector preview) {
        ImportResult.Builder result = new ImportResult.Builder();
        statement.warnings().forEach(result::warn);
        Account account = account(userId, accountId, statement, result);
        if (preview != null && result.accountsCreated > 0) {
            preview.accountCreated(account);
        }
        Batch batch = new Batch(account, statement, categories.session(userId), payees.lookup(userId), review, preview);
        // Bank operations already standing for a record of this statement; each stands for one record only
        Set<Long> claimed = new HashSet<>();
        // Oldest first, so that the categories learned from earlier operations apply to later ones
        for (MonobankPdfStatement.Record record : statement.records().reversed()) {
            if (record.date().isBefore(Transaction.MIN_DATE) || record.date().isAfter(Transaction.MAX_DATE)) {
                result.warn("Operation of " + record.date() + " skipped: implausible date");
                continue;
            }
            if (record.cardAmount().signum() == 0) {
                continue;
            }
            String key = key(statement, record);
            if (review.excludes(key)) {
                result.transactionsSkipped++;
                continue;
            }
            if (matcher.findKnown(userId, key).isPresent()) {
                result.transactionsSkipped++;
                previewDuplicate(batch, record, key);
                continue;
            }
            if (claimTwin(account, record, claimed)) {
                result.transactionsMatched++;
                previewDuplicate(batch, record, key);
                continue;
            }
            importRecord(batch, record, key, result);
        }
        result.payeesCreated = batch.payees().created();
        // The history before the statement is not imported into a new account
        if (result.accountsCreated > 0 && statement.closingBalance() != null) {
            accountService.alignBalance(account, statement.closingBalance());
        }
        return result.build();
    }

    private Account account(Long userId, Long accountId, MonobankPdfStatement statement, ImportResult.Builder result) {
        if (accountId != null) {
            Account account = lookups.account(userId, accountId, "accountId");
            if (!account.getCurrency().equals(statement.currency())) {
                throw ApiException.invalid("accountId", "error.bank.currency");
            }
            return account;
        }
        Optional<Account> byCard =
                accountService.findByCardNumbers(userId, statement.currency(), List.of(statement.cardLast4())).stream()
                        .findFirst();
        if (byCard.isPresent()) {
            return byCard.get();
        }
        boolean credit =
                statement.creditLimit() != null && statement.creditLimit().signum() > 0;
        Account account = new Account(
                userId,
                "Monobank *" + statement.cardLast4(),
                credit ? AccountType.CREDIT_CARD : AccountType.CARD,
                statement.currency());
        account.setCardNumbers(List.of(statement.cardLast4()));
        if (credit) {
            account.setCreditLimit(statement.creditLimit());
        }
        result.accountsCreated++;
        return accounts.save(account);
    }

    /** One import: what every operation needs. */
    private record Batch(
            Account account,
            MonobankPdfStatement statement,
            CategorySuggester.Session suggestions,
            PayeeService.Lookup payees,
            ImportReview review,
            ImportPreviewCollector preview) {}

    /** Takes a bank operation of the same day and amount that no other record of the statement stands for yet. */
    private boolean claimTwin(Account account, MonobankPdfStatement.Record record, Set<Long> claimed) {
        boolean outgoing = record.cardAmount().signum() < 0;
        Optional<Transaction> twin = transactions
                .findBankTwins(
                        account.getUserId(),
                        account,
                        outgoing,
                        record.cardAmount().abs(),
                        record.date())
                .stream()
                .filter(transaction -> !claimed.contains(transaction.getId()))
                .findFirst();
        twin.ifPresent(transaction -> claimed.add(transaction.getId()));
        return twin.isPresent();
    }

    private void importRecord(
            Batch batch, MonobankPdfStatement.Record record, String key, ImportResult.Builder result) {
        Account account = batch.account();
        BigDecimal amount = record.cardAmount().abs();
        TransactionType type = type(record);
        if (matcher.matchExisting(account, type, amount, record.date(), key).isPresent()) {
            result.transactionsMatched++;
            previewDuplicate(batch, record, key);
            return;
        }
        if (record.mcc() == OperationMatcher.TRANSFER_MCC
                // Transfers between own Monobank cards are free: no fee tolerance
                && matcher.mergeTransfer(account, type, amount, record.date(), key, false)
                        .isPresent()) {
            result.transactionsMatched++;
            previewDuplicate(batch, record, key);
            return;
        }
        Transaction transaction = describe(account, batch.statement(), record);
        transaction.setExternalId(key);
        transaction.setBankId(key);
        Payee payee = record.payee() == null
                ? null
                : batch.payees().findOrCreate(Texts.truncate(record.payee(), Payee.MAX_NAME_LENGTH));
        transaction.setPayee(payee);
        // Money back from a merchant is a refund: it goes to the expense category of the purchase
        boolean refund = type == TransactionType.INCOME && record.mcc() != OperationMatcher.TRANSFER_MCC;
        CategoryKind kind = type == TransactionType.INCOME && !refund ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        transaction.replaceSplits(
                List.of(new SplitData(batch.suggestions().suggest(payee, record.mcc(), null, kind), amount, null)));
        batch.review().apply(key, transaction);
        transactions.save(transaction);
        batch.review().saved(key, transaction);
        result.transactionsImported++;
        if (batch.preview() != null) {
            batch.preview().add(key, transaction, false);
        }
    }

    /** An operation that is already in the ledger, as it appears in the preview. */
    private static void previewDuplicate(Batch batch, MonobankPdfStatement.Record record, String key) {
        if (batch.preview() == null) {
            return;
        }
        Transaction transaction = describe(batch.account(), batch.statement(), record);
        // Not saved: only the name is shown
        transaction.setPayee(
                record.payee() == null
                        ? null
                        : new Payee(
                                batch.account().getUserId(), Texts.truncate(record.payee(), Payee.MAX_NAME_LENGTH)));
        batch.preview().add(key, transaction, true);
    }

    /** The transaction of the operation without the payee, the category and the ids. */
    private static Transaction describe(
            Account account, MonobankPdfStatement statement, MonobankPdfStatement.Record record) {
        Transaction transaction = new Transaction(account.getUserId());
        transaction.setSource(TransactionSource.MONOBANK);
        transaction.setType(type(record));
        transaction.setDate(record.date());
        transaction.setAccount(account);
        transaction.setAmount(record.cardAmount().abs());
        transaction.setMcc(record.mcc());
        if (!record.operationCurrency().equals(statement.currency())) {
            transaction.setOriginalAmount(record.operationAmount().abs());
            transaction.setOriginalCurrency(record.operationCurrency());
        }
        transaction.setComment(Texts.truncate(comment(record), Transaction.MAX_COMMENT_LENGTH));
        return transaction;
    }

    /** The details after the payee, e.g. the phone number of a top-up; null when the payee is all there is. */
    static String comment(MonobankPdfStatement.Record record) {
        if (record.payee() == null
                || record.details().length() <= record.payee().length()) {
            return null;
        }
        String rest = record.details().substring(record.payee().length()).trim();
        return rest.isEmpty() ? null : rest;
    }

    private static TransactionType type(MonobankPdfStatement.Record record) {
        return record.cardAmount().signum() > 0 ? TransactionType.INCOME : TransactionType.EXPENSE;
    }

    /**
     * Identifies an operation across overlapping statements; the balance makes collisions between different
     * operations practically impossible.
     */
    static String key(MonobankPdfStatement statement, MonobankPdfStatement.Record record) {
        return String.join(
                "|",
                KEY_PREFIX,
                statement.currency(),
                record.date().toString(),
                record.time().toString(),
                record.cardAmount().toPlainString(),
                record.balanceAfter().toPlainString());
    }
}
