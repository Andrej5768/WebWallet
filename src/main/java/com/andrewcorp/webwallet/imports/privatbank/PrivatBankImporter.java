package com.andrewcorp.webwallet.imports.privatbank;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.util.Texts;
import com.andrewcorp.webwallet.imports.ImportResult;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports the operations of a parsed PrivatBank statement into an account. Overlapping statements and operations
 * that are already known (entered manually or imported from Zen Money) do not create duplicates.
 */
@Service
public class PrivatBankImporter {

    static final String SOURCE = "PRIVATBANK";

    private static final int MAX_PAYEE_LENGTH = 200;
    private static final int MAX_COMMENT_LENGTH = 1000;
    private static final int MAX_BANK_CATEGORY_LENGTH = 100;

    private static final Pattern RECIPIENT = Pattern.compile("отримувач (.+?), банк");
    private static final Pattern SENDER = Pattern.compile("(?:^|, )від ([^,]+)$");
    private static final Pattern PAYMENT_COMMENT = Pattern.compile("Коментар до платежу: (.*)$");

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final TransactionRepository transactions;
    private final OperationMatcher matcher;
    private final PayeeService payees;
    private final CategorySuggester categories;

    public PrivatBankImporter(
            AccountRepository accounts,
            AccountService accountService,
            TransactionRepository transactions,
            OperationMatcher matcher,
            PayeeService payees,
            CategorySuggester categories) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.transactions = transactions;
        this.matcher = matcher;
        this.payees = payees;
        this.categories = categories;
    }

    /**
     * @param accountId account to import into; when null, the account with the card number of the statement is used,
     *                  or a new one is created
     */
    @Transactional
    public ImportResult importStatement(Long userId, Long accountId, PrivatStatement statement) {
        ImportResult.Builder result = new ImportResult.Builder();
        statement.warnings().forEach(result::warn);
        Account account = account(userId, accountId, statement, result);
        Set<String> imported = new HashSet<>(transactions.findExternalIds(userId, TransactionSource.PRIVATBANK));

        // Oldest first, so that the categories learned from earlier operations apply to later ones
        for (PrivatStatement.Record record : statement.records().reversed()) {
            String key = key(statement, record);
            if (imported.contains(key) || matcher.findKnown(userId, key).isPresent()) {
                result.transactionsSkipped++;
                continue;
            }
            importRecord(account, statement, record, key, result);
            imported.add(key);
        }
        // The history before the statement is not imported into a new account
        if (result.accountsCreated > 0 && statement.closingBalance() != null) {
            accountService.alignBalance(account, statement.closingBalance());
        }
        return result.build();
    }

    private Account account(Long userId, Long accountId, PrivatStatement statement, ImportResult.Builder result) {
        if (accountId != null) {
            Account account = accounts.findByIdAndUserId(accountId, userId)
                    .orElseThrow(() -> ApiException.invalid("accountId", "error.notFound"));
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
                "PrivatBank *" + statement.cardLast4(),
                credit ? AccountType.CREDIT_CARD : AccountType.CARD,
                statement.currency());
        account.setCardNumbers(List.of(statement.cardLast4()));
        if (credit) {
            account.setCreditLimit(statement.creditLimit());
        }
        result.accountsCreated++;
        return accounts.save(account);
    }

    private void importRecord(
            Account account,
            PrivatStatement statement,
            PrivatStatement.Record record,
            String key,
            ImportResult.Builder result) {
        Long userId = account.getUserId();
        BigDecimal amount = record.cardAmount().abs();
        if (amount.signum() == 0) {
            return;
        }
        TransactionType type = record.cardAmount().signum() > 0 ? TransactionType.INCOME : TransactionType.EXPENSE;

        if (matcher.matchExisting(account, type, amount, record.date(), key).isPresent()) {
            result.transactionsMatched++;
            return;
        }
        if (OperationMatcher.TRANSFER_CATEGORIES.contains(record.bankCategory())
                && matcher.mergeTransfer(account, type, amount, record.date(), key)
                        .isPresent()) {
            result.transactionsMatched++;
            return;
        }

        Transaction transaction = new Transaction(userId);
        transaction.setSource(TransactionSource.PRIVATBANK);
        transaction.setExternalId(key);
        transaction.setBankId(key);
        transaction.setType(type);
        transaction.setDate(record.date());
        transaction.setAccount(account);
        transaction.setAmount(amount);
        if (!record.operationCurrency().equals(statement.currency())) {
            transaction.setOriginalAmount(record.operationAmount().abs());
            transaction.setOriginalCurrency(record.operationCurrency());
        }
        Payee payee = payee(userId, record);
        transaction.setPayee(payee);
        String bankCategory = Texts.truncate(record.bankCategory(), MAX_BANK_CATEGORY_LENGTH);
        transaction.setBankCategory(bankCategory);
        transaction.setComment(Texts.truncate(
                Optional.ofNullable(paymentComment(record)).orElse(record.bankCategory()), MAX_COMMENT_LENGTH));
        CategoryKind kind = type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        transaction.replaceSplits(
                List.of(new SplitData(categories.suggest(userId, payee, null, bankCategory, kind), amount, null)));
        transactions.save(transaction);
        result.transactionsImported++;
    }

    private Payee payee(Long userId, PrivatStatement.Record record) {
        String name = find(RECIPIENT, record.details());
        if (name == null && record.cardAmount().signum() > 0) {
            name = find(SENDER, record.details());
        }
        String trimmed = Texts.truncate(name, MAX_PAYEE_LENGTH);
        return trimmed == null ? null : payees.findOrCreate(userId, trimmed);
    }

    private static String paymentComment(PrivatStatement.Record record) {
        String comment = find(PAYMENT_COMMENT, record.details());
        return comment == null || comment.equals("-") ? null : comment;
    }

    /**
     * Identifies an operation across overlapping statements; the balance makes collisions between different
     * operations practically impossible.
     */
    static String key(PrivatStatement statement, PrivatStatement.Record record) {
        return String.join(
                "|",
                statement.currency(),
                record.date().toString(),
                record.time().toString(),
                record.cardAmount().toPlainString(),
                record.balanceAfter().toPlainString());
    }

    private static String find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
