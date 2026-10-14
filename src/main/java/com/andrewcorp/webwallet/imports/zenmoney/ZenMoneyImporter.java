package com.andrewcorp.webwallet.imports.zenmoney;

import com.andrewcorp.webwallet.budget.BudgetAssignment;
import com.andrewcorp.webwallet.budget.BudgetRepository;
import com.andrewcorp.webwallet.common.util.Lists;
import com.andrewcorp.webwallet.common.util.Texts;
import com.andrewcorp.webwallet.imports.ImportResult;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountService;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.payee.PayeeService;
import com.andrewcorp.webwallet.ledger.transaction.OperationMatcher;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.Transaction.SplitData;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionSource;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.user.User;
import com.andrewcorp.webwallet.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports accounts, categories, payees and transactions from a Zen Money dump. Imported records keep their Zen Money
 * ids, so a repeated import adds only what is new. Monthly budgets become envelope assignments; planned payments
 * are not imported yet.
 */
@Service
public class ZenMoneyImporter {

    static final String SOURCE = "ZENMONEY";
    /** Budget of the whole month, added on top of the category budgets in Zen Money. */
    private static final String TOTAL_BUDGET_TAG = "00000000-0000-0000-0000-000000000000";

    private static final int MAX_PAYEE_LENGTH = 200;
    private static final int MAX_COMMENT_LENGTH = 1000;
    /** Amounts are stored as numeric(19, 4). */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1e15");

    private static final BigDecimal MAX_INTEREST_RATE = new BigDecimal("1000");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(2100, 1, 1);
    /** Imported transactions are flushed and detached in chunks, so that a history of years fits in memory. */
    private static final int FLUSH_EVERY = 500;

    private final UserRepository users;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final CategoryRepository categories;
    private final PayeeService payees;
    private final TransactionRepository transactions;
    private final BudgetRepository budget;
    private final EntityManager entityManager;

    public ZenMoneyImporter(
            UserRepository users,
            AccountRepository accounts,
            AccountService accountService,
            CategoryRepository categories,
            PayeeService payees,
            TransactionRepository transactions,
            BudgetRepository budget,
            EntityManager entityManager) {
        this.users = users;
        this.accounts = accounts;
        this.accountService = accountService;
        this.categories = categories;
        this.payees = payees;
        this.transactions = transactions;
        this.budget = budget;
        this.entityManager = entityManager;
    }

    @Transactional
    public ImportResult importDump(Long userId, ZenMoneyDump dump) {
        return new Run(userId, dump).execute();
    }

    /** State of one import. */
    private class Run {

        private final Long userId;
        private final ZenMoneyDump dump;
        private final ImportResult.Builder result = new ImportResult.Builder();
        private final Map<Integer, String> currencies = new HashMap<>();
        private final Map<String, Account> accountsById = new HashMap<>();
        /** Accounts created by this import; only their opening balances are aligned with Zen Money. */
        private final Set<String> createdAccounts = new HashSet<>();

        private final Map<String, ZenMoneyDump.Tag> tagsById = new HashMap<>();
        /** Key: Zen Money tag id and the kind; a tag used for both expenses and income becomes two categories. */
        private final Map<String, Category> categoriesByKey = new HashMap<>();

        private final Map<String, String> merchantNames = new HashMap<>();
        private PayeeService.Lookup payeeLookup;

        Run(Long userId, ZenMoneyDump dump) {
            this.userId = userId;
            this.dump = dump;
        }

        ImportResult execute() {
            Lists.orEmpty(dump.instrument()).stream()
                    .filter(instrument -> instrument.shortTitle() != null
                            && CURRENCY.matcher(instrument.shortTitle()).matches())
                    .forEach(instrument -> currencies.put(instrument.id(), instrument.shortTitle()));
            Lists.orEmpty(dump.tag()).forEach(tag -> tagsById.put(tag.id(), tag));
            Lists.orEmpty(dump.merchant()).forEach(merchant -> merchantNames.put(merchant.id(), merchant.title()));
            payeeLookup = payees.lookup(userId);

            importBaseCurrency();
            Lists.orEmpty(dump.account()).forEach(this::importAccount);
            importTransactions();
            importBudgets();
            alignBalances();
            result.payeesCreated = payeeLookup.created();
            return result.build();
        }

        private void importBaseCurrency() {
            Lists.orEmpty(dump.user()).stream()
                    .map(ZenMoneyDump.User::currency)
                    .filter(Objects::nonNull)
                    .map(currencies::get)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .ifPresent(currency -> {
                        User user = users.findById(userId).orElseThrow();
                        user.setBaseCurrency(currency);
                    });
        }

        private void importAccount(ZenMoneyDump.Account source) {
            String currency = currencies.get(source.instrument());
            if (currency == null) {
                result.warn("Account '" + source.title() + "' skipped: unknown currency " + source.instrument());
                return;
            }
            Account account = accounts.findByUserIdAndExternalSourceAndExternalId(userId, SOURCE, source.id())
                    .orElse(null);
            AccountType type = type(source);
            if (account == null) {
                account = new Account(userId, name(source.title(), "Account"), type, currency);
                account.setExternal(SOURCE, source.id());
                createdAccounts.add(source.id());
                result.accountsCreated++;
            } else {
                account.setName(name(source.title(), "Account"));
                account.setType(type);
                result.accountsUpdated++;
            }
            account.setInBudget(type.inBudgetByDefault() && !Boolean.FALSE.equals(source.inBalance()));
            account.setArchived(Boolean.TRUE.equals(source.archive()));
            account.setCreditLimit(valid(positive(source.creditLimit())));
            account.setInterestRate(
                    source.percent() != null && source.percent().compareTo(MAX_INTEREST_RATE) < 0
                            ? positive(source.percent())
                            : null);
            account.setCardNumbers(
                    source.syncID() == null || source.syncID().isEmpty() ? null : List.copyOf(source.syncID()));
            accountsById.put(source.id(), accounts.save(account));
        }

        private void importTransactions() {
            Set<String> imported = transactions.findExternalIds(userId, TransactionSource.ZENMONEY);
            Set<String> bankIds = new HashSet<>(transactions.findBankIds(userId));
            bankIds.addAll(transactions.findToBankIds(userId));
            for (ZenMoneyDump.Transaction source : Lists.orEmpty(dump.transaction())) {
                if (Boolean.TRUE.equals(source.deleted()) || imported.contains(source.id())) {
                    result.transactionsSkipped++;
                    continue;
                }
                Transaction transaction = toTransaction(source);
                if (transaction == null) {
                    continue;
                }
                if (bankIds.contains(transaction.getBankId()) || bankIds.contains(transaction.getToBankId())) {
                    // Already imported from the bank directly (Monobank, PrivatBank)
                    result.transactionsSkipped++;
                    continue;
                }
                transactions.save(transaction);
                Stream.of(transaction.getBankId(), transaction.getToBankId())
                        .filter(Objects::nonNull)
                        .forEach(bankIds::add);
                if (++result.transactionsImported % FLUSH_EVERY == 0) {
                    // The accounts, categories and payees kept by this import stay usable as references
                    entityManager.flush();
                    entityManager.clear();
                }
            }
        }

        private Transaction toTransaction(ZenMoneyDump.Transaction source) {
            Account incomeAccount = accountsById.get(source.incomeAccount());
            Account outcomeAccount = accountsById.get(source.outcomeAccount());
            if (incomeAccount == null || outcomeAccount == null) {
                result.warn("Transaction " + source.id() + " skipped: unknown account");
                return null;
            }
            BigDecimal income = amount(source.income());
            BigDecimal outcome = amount(source.outcome());
            LocalDate date = date(source.date());
            if (date == null || !isValidAmount(income) || !isValidAmount(outcome)) {
                result.warn("Transaction " + source.id() + " skipped: invalid date or amount");
                return null;
            }

            Transaction transaction = new Transaction(userId);
            transaction.setSource(TransactionSource.ZENMONEY);
            transaction.setExternalId(source.id());
            transaction.setDate(date);
            transaction.setComment(Texts.truncate(source.comment(), MAX_COMMENT_LENGTH));
            transaction.setPayee(payee(source));
            String outcomeBankId = OperationMatcher.normalizeBankId(source.outcomeBankID());
            String incomeBankId = OperationMatcher.normalizeBankId(source.incomeBankID());

            boolean sameAccount = incomeAccount == outcomeAccount;
            if (!sameAccount && income.signum() > 0 && outcome.signum() > 0) {
                transaction.setType(TransactionType.TRANSFER);
                transaction.setAccount(outcomeAccount);
                transaction.setAmount(outcome);
                transaction.setToAccount(incomeAccount);
                transaction.setToAmount(income);
                transaction.setBankId(outcomeBankId);
                transaction.setToBankId(incomeBankId);
                return transaction;
            }

            // An expense or income; a "transfer" with one empty side is treated the same way
            BigDecimal net = income.subtract(outcome);
            if (net.signum() == 0) {
                result.warn("Transaction " + source.id() + " of " + source.date() + " skipped: zero amount");
                return null;
            }
            boolean isIncome = net.signum() > 0;
            Account account = isIncome ? incomeAccount : outcomeAccount;
            CategoryKind kind = isIncome ? CategoryKind.INCOME : CategoryKind.EXPENSE;
            transaction.setType(isIncome ? TransactionType.INCOME : TransactionType.EXPENSE);
            transaction.setAccount(account);
            transaction.setAmount(net.abs());
            transaction.setBankId(
                    isIncome
                            ? Optional.ofNullable(incomeBankId).orElse(outcomeBankId)
                            : Optional.ofNullable(outcomeBankId).orElse(incomeBankId));
            original(
                    transaction,
                    account,
                    isIncome ? source.opIncome() : source.opOutcome(),
                    isIncome ? source.opIncomeInstrument() : source.opOutcomeInstrument());
            String tagId = source.tag() == null || source.tag().isEmpty()
                    ? null
                    : source.tag().get(0);
            transaction.replaceSplits(List.of(new SplitData(category(tagId, kind), net.abs(), null)));
            return transaction;
        }

        /** Monthly expense plans become envelope assignments of the month. */
        private void importBudgets() {
            for (ZenMoneyDump.Budget source : Lists.orEmpty(dump.budget())) {
                LocalDate date = date(source.date());
                if (source.tag() == null
                        || TOTAL_BUDGET_TAG.equals(source.tag())
                        || source.outcome() == null
                        || source.outcome().signum() <= 0
                        || !isValidAmount(source.outcome())
                        || date == null) {
                    continue;
                }
                Category category = category(source.tag(), CategoryKind.EXPENSE);
                if (category == null) {
                    continue;
                }
                LocalDate month = date.withDayOfMonth(1);
                budget.findByUserIdAndMonthAndCategory(userId, month, category)
                        .ifPresentOrElse(
                                assignment -> assignment.setAmount(source.outcome()),
                                () -> budget.save(new BudgetAssignment(userId, month, category, source.outcome())));
                result.budgetsImported++;
            }
        }

        private void original(Transaction transaction, Account account, BigDecimal amount, Integer instrument) {
            String currency = instrument == null ? null : currencies.get(instrument);
            if (amount != null
                    && amount.signum() > 0
                    && isValidAmount(amount)
                    && currency != null
                    && !currency.equals(account.getCurrency())) {
                transaction.setOriginalAmount(amount);
                transaction.setOriginalCurrency(currency);
            }
        }

        private Category category(String tagId, CategoryKind kind) {
            return category(tagId, kind, true);
        }

        /**
         * Categories have two levels, so only the parent of a tag is imported and not the parent's parent; this also
         * stops at cycles of parents in a malformed export.
         */
        private Category category(String tagId, CategoryKind kind, boolean withParent) {
            ZenMoneyDump.Tag tag = tagId == null ? null : tagsById.get(tagId);
            if (tag == null) {
                return null;
            }
            String key = tag.id() + ":" + kind;
            Category cached = categoriesByKey.get(key);
            if (cached != null) {
                return cached;
            }
            // A tag is imported with its natural kind under its own id; a twin of the other kind gets a suffix
            String externalId = kind == naturalKind(tag) ? tag.id() : key;
            Category category = categories
                    .findByUserIdAndExternalSourceAndExternalId(userId, SOURCE, externalId)
                    .orElseGet(() -> {
                        Category parent = !withParent
                                        || tag.parent() == null
                                        || tag.parent().equals(tag.id())
                                ? null
                                : category(tag.parent(), kind, false);
                        Category created = new Category(
                                userId, parent == null ? null : parent.getId(), name(tag.title(), "Category"), kind);
                        created.setArchived(Boolean.TRUE.equals(tag.archive()));
                        created.setExternal(SOURCE, externalId);
                        result.categoriesCreated++;
                        return categories.save(created);
                    });
            categoriesByKey.put(key, category);
            return category;
        }

        private Payee payee(ZenMoneyDump.Transaction source) {
            String name = source.merchant() != null ? merchantNames.get(source.merchant()) : null;
            if (name == null || name.isBlank()) {
                name = source.payee();
            }
            if (name == null || name.isBlank()) {
                return null;
            }
            return payeeLookup.findOrCreate(Texts.truncate(name, MAX_PAYEE_LENGTH));
        }

        /**
         * Zen Money keeps the current balance of an account; the opening balance of a new account is chosen so that
         * the balance computed from the imported transactions is the same. Existing accounts keep their own, which
         * may include corrections by the user and operations imported from banks.
         */
        private void alignBalances() {
            for (ZenMoneyDump.Account source : Lists.orEmpty(dump.account())) {
                Account account = accountsById.get(source.id());
                if (account == null
                        || !createdAccounts.contains(source.id())
                        || source.balance() == null
                        || source.balance().abs().compareTo(MAX_AMOUNT) >= 0) {
                    continue;
                }
                // The entity may have been detached while importing the transactions
                accountService.alignBalance(accounts.getReferenceById(account.getId()), source.balance());
            }
        }
    }

    private static AccountType type(ZenMoneyDump.Account source) {
        String type = source.type() == null ? "" : source.type();
        if (Boolean.TRUE.equals(source.savings()) && !type.equals("loan") && !type.equals("debt")) {
            return AccountType.SAVINGS;
        }
        return switch (type) {
            case "cash" -> AccountType.CASH;
            case "ccard" -> positive(source.creditLimit()) != null ? AccountType.CREDIT_CARD : AccountType.CARD;
            case "loan" -> AccountType.LOAN;
            case "deposit" -> AccountType.DEPOSIT;
            case "debt" -> AccountType.DEBT;
            default -> AccountType.CARD; // checking, emoney
        };
    }

    private static CategoryKind naturalKind(ZenMoneyDump.Tag tag) {
        return Boolean.TRUE.equals(tag.showIncome()) && Boolean.FALSE.equals(tag.showOutcome())
                ? CategoryKind.INCOME
                : CategoryKind.EXPENSE;
    }

    private static BigDecimal amount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private static boolean isValidAmount(BigDecimal amount) {
        return amount.signum() >= 0 && amount.compareTo(MAX_AMOUNT) < 0;
    }

    private static BigDecimal valid(BigDecimal amount) {
        return amount != null && isValidAmount(amount) ? amount : null;
    }

    /** Null for a missing, malformed or implausible date. */
    private static LocalDate date(String text) {
        if (text == null) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(text);
            return date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE) ? null : date;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal positive(BigDecimal amount) {
        return amount != null && amount.signum() > 0 ? amount : null;
    }

    private static String name(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : Texts.truncate(name, 100);
    }
}
