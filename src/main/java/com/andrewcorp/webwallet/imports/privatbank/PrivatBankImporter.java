package com.andrewcorp.webwallet.imports.privatbank;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.imports.ImportResult;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountService;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.payee.PayeeService;
import com.andrewcorp.webwallet.ledger.rule.CategorySuggester;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.Transaction.SplitData;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionSource;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
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
    private static final int MATCH_DAYS = 3;
    /** Transfers between own cards lose up to ~4% in fees, e.g. 10 300 leaves Privat and 10 000 arrives elsewhere. */
    private static final BigDecimal MIN_TRANSFER_RATIO = new BigDecimal("0.96");

    private static final int MAX_PAYEE_LENGTH = 200;
    private static final int MAX_COMMENT_LENGTH = 1000;
    private static final int MAX_BANK_CATEGORY_LENGTH = 100;

    private static final Pattern RECIPIENT = Pattern.compile("отримувач (.+?), банк");
    private static final Pattern SENDER = Pattern.compile("(?:^|, )від ([^,]+)$");
    private static final Pattern PAYMENT_COMMENT = Pattern.compile("Коментар до платежу: (.*)$");
    private static final Set<String> TRANSFER_CATEGORIES =
            Set.of("Перекази", "Зарахування переказу", "Зарахування зі своєї картки");

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final TransactionRepository transactions;
    private final PayeeService payees;
    private final CategorySuggester categories;
    private final EntityManager entityManager;

    public PrivatBankImporter(
            AccountRepository accounts,
            AccountService accountService,
            TransactionRepository transactions,
            PayeeService payees,
            CategorySuggester categories,
            EntityManager entityManager) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.transactions = transactions;
        this.payees = payees;
        this.categories = categories;
        this.entityManager = entityManager;
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
            if (imported.contains(key)
                    || transactions
                            .findFirstByUserIdAndBankReferenceEndingWith(userId, key)
                            .isPresent()) {
                result.transactionsSkipped++;
                continue;
            }
            importRecord(account, statement, record, key, result);
            imported.add(key);
        }
        if (result.accountsCreated > 0 && statement.closingBalance() != null) {
            alignBalance(account, statement.closingBalance());
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
        Optional<Account> byCard = accounts.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .filter(candidate -> candidate.getCurrency().equals(statement.currency()))
                .filter(candidate -> candidate.getCardNumbers() != null
                        && candidate.getCardNumbers().contains(statement.cardLast4()))
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

        Optional<Transaction> known = transactions
                .findMatchCandidates(
                        userId,
                        account,
                        type,
                        amount,
                        record.date().minusDays(MATCH_DAYS),
                        record.date().plusDays(MATCH_DAYS),
                        TransactionSource.PRIVATBANK)
                .stream()
                .min(Comparator.comparingLong(
                        candidate -> Math.abs(ChronoUnit.DAYS.between(candidate.getDate(), record.date()))));
        if (known.isPresent()) {
            known.get().setBankReference(key);
            result.transactionsMatched++;
            return;
        }

        if (TRANSFER_CATEGORIES.contains(record.bankCategory()) && mergeTransfer(account, record, type, amount, key)) {
            result.transactionsMatched++;
            return;
        }

        Transaction transaction = new Transaction(userId);
        transaction.setSource(TransactionSource.PRIVATBANK);
        transaction.setExternalId(key);
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
        String bankCategory = truncate(record.bankCategory(), MAX_BANK_CATEGORY_LENGTH);
        transaction.setBankCategory(bankCategory);
        transaction.setComment(truncate(
                Optional.ofNullable(paymentComment(record)).orElse(record.bankCategory()), MAX_COMMENT_LENGTH));
        CategoryKind kind = type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        transaction.replaceSplits(
                List.of(new SplitData(categories.suggest(userId, payee, null, bankCategory, kind), amount, null)));
        transactions.save(transaction);
        result.transactionsImported++;
    }

    /**
     * A transfer to or from another own account (e.g. Monobank) is already there as an income or expense; it becomes
     * one transfer with both amounts. The fee makes the amounts differ by up to ~4%.
     */
    private boolean mergeTransfer(
            Account account, PrivatStatement.Record record, TransactionType type, BigDecimal amount, String key) {
        boolean outgoing = type == TransactionType.EXPENSE;
        BigDecimal min = outgoing ? amount.multiply(MIN_TRANSFER_RATIO) : amount;
        BigDecimal max = outgoing ? amount : amount.divide(MIN_TRANSFER_RATIO, 4, RoundingMode.HALF_UP);
        TransactionType otherType = outgoing ? TransactionType.INCOME : TransactionType.EXPENSE;
        Optional<Transaction> counterpart = transactions
                .findTransferCandidates(
                        account.getUserId(),
                        otherType,
                        account,
                        account.getCurrency(),
                        record.date().minusDays(1),
                        record.date().plusDays(1),
                        min,
                        max)
                .stream()
                .min(Comparator.comparing(
                        candidate -> candidate.getAmount().subtract(amount).abs()));
        if (counterpart.isEmpty()) {
            return false;
        }
        Transaction transfer = counterpart.get();
        Account other = transfer.getAccount();
        BigDecimal otherAmount = transfer.getAmount();
        transfer.setType(TransactionType.TRANSFER);
        transfer.replaceSplits(List.of());
        transfer.setPayee(null);
        if (outgoing) {
            transfer.setAccount(account);
            transfer.setAmount(amount);
            transfer.setToAccount(other);
            transfer.setToAmount(otherAmount);
        } else {
            transfer.setToAccount(account);
            transfer.setToAmount(amount);
        }
        transfer.setBankReference(key);
        return true;
    }

    private Payee payee(Long userId, PrivatStatement.Record record) {
        String name = find(RECIPIENT, record.details());
        if (name == null && record.cardAmount().signum() > 0) {
            name = find(SENDER, record.details());
        }
        String trimmed = truncate(name, MAX_PAYEE_LENGTH);
        return trimmed == null ? null : payees.findOrCreate(userId, trimmed);
    }

    private static String paymentComment(PrivatStatement.Record record) {
        String comment = find(PAYMENT_COMMENT, record.details());
        return comment == null || comment.equals("-") ? null : comment;
    }

    /** The account opening balance is set so that the balance equals the statement closing balance. */
    private void alignBalance(Account account, BigDecimal closingBalance) {
        entityManager.flush();
        BigDecimal balance =
                accountService.balances(account.getUserId()).getOrDefault(account.getId(), account.getOpeningBalance());
        account.setOpeningBalance(account.getOpeningBalance().add(closingBalance.subtract(balance)));
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

    private static String truncate(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
