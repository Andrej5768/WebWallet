package com.andrewcorp.webwallet.tax;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.currency.ExchangeRateService;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionSplit;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.tax.TaxDtos.FopIncome;
import com.andrewcorp.webwallet.tax.TaxDtos.FopQuarter;
import com.andrewcorp.webwallet.tax.TaxDtos.FopReport;
import com.andrewcorp.webwallet.tax.TaxDtos.FopTotals;
import com.andrewcorp.webwallet.tax.TaxDtos.IncomeReason;
import com.andrewcorp.webwallet.tax.TaxDtos.TransactionTaxResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The taxes of a sole proprietor (ФОП) for a year. The income is the income of the accounts of type {@code FOP}, the
 * part of incomes on other accounts in the chosen income categories, and the transactions the user added; the user
 * may take any of them out. Group 3 pays shares of the quarter's income, groups 1 and 2 fixed amounts a month; the
 * social contribution is a share of the minimum wage a month.
 */
@Service
public class FopTaxService {

    private static final int DECLARATION_DAYS_GROUP_3 = 40;
    private static final int PAYMENT_DAYS_AFTER_DECLARATION = 10;
    private static final int DECLARATION_DAYS_GROUPS_1_2 = 60;

    private final TaxSettingsService settings;
    private final TaxTransactionMarkRepository marks;
    private final TaxFopPaymentRepository payments;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final TransactionRepository transactions;
    private final ExchangeRateService exchangeRates;

    public FopTaxService(
            TaxSettingsService settings,
            TaxTransactionMarkRepository marks,
            TaxFopPaymentRepository payments,
            AccountRepository accounts,
            CategoryRepository categories,
            TransactionRepository transactions,
            ExchangeRateService exchangeRates) {
        this.settings = settings;
        this.marks = marks;
        this.payments = payments;
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.exchangeRates = exchangeRates;
    }

    @Transactional(readOnly = true)
    public FopReport report(Long userId, int year) {
        TaxRates rates = settings.rates(userId, year);
        List<Long> fopAccounts = accounts.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .filter(account -> account.getType() == AccountType.FOP)
                .map(Account::getId)
                .toList();
        Set<Long> incomeCategories = withSubcategories(userId, settings.incomeCategoryIds(userId));
        Map<Long, Boolean> marked = marks.findByUserId(userId).stream()
                .collect(Collectors.toMap(TaxTransactionMark::getTransactionId, TaxTransactionMark::isIncluded));
        Set<Long> added = new HashSet<>();
        marked.forEach((id, included) -> {
            if (included) {
                added.add(id);
            }
        });
        // An empty list does not do in the query
        added.add(-1L);

        CurrencyConverter converter = exchangeRates.converterTo(ExchangeRateService.UAH);
        List<FopIncome> incomes = new ArrayList<>();
        BigDecimal[] quarterIncome = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        List<Transaction> candidates =
                transactions.findTaxCandidates(userId, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), added);
        for (Transaction transaction : candidates) {
            Boolean mark = marked.get(transaction.getId());
            Counted counted = counted(transaction, mark, fopAccounts, incomeCategories);
            if (counted == null) {
                continue;
            }
            IncomeReason reason = counted.reason();
            BigDecimal amount = counted.amount();
            boolean included = !Boolean.FALSE.equals(mark);
            String currency = transaction.getAccount().getCurrency();
            BigDecimal amountUah = converter.convertIfKnown(amount, currency, transaction.getDate());
            BigDecimal rate = currency.equals(ExchangeRateService.UAH)
                    ? null
                    : converter.convertIfKnown(BigDecimal.ONE, currency, transaction.getDate());
            int quarter = (transaction.getDate().getMonthValue() - 1) / 3 + 1;
            if (included && amountUah != null) {
                quarterIncome[quarter - 1] = quarterIncome[quarter - 1].add(amountUah);
            }
            incomes.add(new FopIncome(
                    transaction.getId(),
                    transaction.getDate(),
                    quarter,
                    transaction.getAccount().getId(),
                    transaction.getAccount().getName(),
                    transaction.getPayee() != null ? transaction.getPayee().getName() : null,
                    transaction.getComment(),
                    amount,
                    currency,
                    rate != null ? rate.setScale(4, RoundingMode.HALF_UP) : null,
                    amountUah != null ? amountUah.setScale(2, RoundingMode.HALF_UP) : null,
                    included,
                    reason));
        }
        incomes.sort(Comparator.comparing(FopIncome::date)
                .thenComparing(FopIncome::transactionId)
                .reversed());

        Set<Integer> paid = payments.findByUserIdAndYear(userId, year).stream()
                .map(TaxFopPayment::getQuarter)
                .collect(Collectors.toSet());
        List<FopQuarter> quarters = new ArrayList<>();
        BigDecimal[] totals = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (int quarter = 1; quarter <= 4; quarter++) {
            BigDecimal amount = quarterIncome[quarter - 1].setScale(2, RoundingMode.HALF_UP);
            boolean group3 = rates.fopGroup() == 3;
            BigDecimal single = group3
                    ? TaxRates.share(amount, rates.singleTaxRate())
                    : rates.singleTaxMonthly().multiply(BigDecimal.valueOf(3));
            BigDecimal military = group3
                    ? TaxRates.share(amount, rates.fopMilitaryRate())
                    : rates.fopMilitaryMonthly().multiply(BigDecimal.valueOf(3));
            BigDecimal esv = rates.esvMonthly().multiply(BigDecimal.valueOf(3));
            BigDecimal total = single.add(military).add(esv);
            LocalDate end = LocalDate.of(year, quarter * 3, 1).plusMonths(1).minusDays(1);
            LocalDate declaration;
            LocalDate taxDue;
            if (group3) {
                declaration = workday(end.plusDays(DECLARATION_DAYS_GROUP_3));
                taxDue = workday(declaration.plusDays(PAYMENT_DAYS_AFTER_DECLARATION));
            } else {
                declaration = quarter == 4 ? workday(end.plusDays(DECLARATION_DAYS_GROUPS_1_2)) : null;
                taxDue = workday(LocalDate.of(year, quarter * 3 - 2, 20));
            }
            LocalDate esvDue = workday(end.plusDays(20));
            quarters.add(new FopQuarter(
                    quarter,
                    amount,
                    single,
                    military,
                    esv,
                    total,
                    declaration,
                    taxDue,
                    esvDue,
                    paid.contains(quarter)));
            totals[0] = totals[0].add(amount);
            totals[1] = totals[1].add(single);
            totals[2] = totals[2].add(military);
            totals[3] = totals[3].add(esv);
            totals[4] = totals[4].add(total);
        }
        return new FopReport(
                year,
                rates,
                fopAccounts,
                quarters,
                new FopTotals(totals[0], totals[1], totals[2], totals[3], totals[4]),
                rates.incomeLimit(),
                incomes,
                converter.unconverted());
    }

    /** Whether a transaction is income of the sole proprietor, why, and how much of it. */
    @Transactional(readOnly = true)
    public TransactionTaxResponse state(Long userId, Long transactionId) {
        Transaction transaction =
                transactions.findByIdAndUserId(transactionId, userId).orElseThrow(ApiException::notFound);
        Boolean mark = marks.findById(transactionId)
                .map(TaxTransactionMark::isIncluded)
                .orElse(null);
        List<Long> fopAccounts = accounts.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .filter(account -> account.getType() == AccountType.FOP)
                .map(Account::getId)
                .toList();
        Counted counted =
                counted(transaction, mark, fopAccounts, withSubcategories(userId, settings.incomeCategoryIds(userId)));
        // Without the mark: what the rules alone would say
        Counted byRules = Boolean.TRUE.equals(mark)
                ? counted(transaction, null, fopAccounts, withSubcategories(userId, settings.incomeCategoryIds(userId)))
                : counted;
        return new TransactionTaxResponse(
                mark,
                counted != null && !Boolean.FALSE.equals(mark),
                counted != null ? counted.reason() : null,
                byRules != null ? byRules.reason() : null);
    }

    private record Counted(IncomeReason reason, BigDecimal amount) {}

    /** Why the transaction is income of the sole proprietor and how much of it; null when it is not. */
    private static Counted counted(
            Transaction transaction, Boolean mark, List<Long> fopAccounts, Set<Long> incomeCategories) {
        boolean income = transaction.getType() == TransactionType.INCOME;
        if (Boolean.TRUE.equals(mark)) {
            return new Counted(IncomeReason.MANUAL, transaction.getAmount());
        }
        if (income && fopAccounts.contains(transaction.getAccount().getId())) {
            return new Counted(IncomeReason.ACCOUNT, transaction.getAmount());
        }
        BigDecimal amount = categoryAmount(transaction, incomeCategories);
        return income && amount.signum() != 0 ? new Counted(IncomeReason.CATEGORY, amount) : null;
    }

    /** The part of the transaction in the categories; a split without a category counts nothing. */
    private static BigDecimal categoryAmount(Transaction transaction, Set<Long> incomeCategories) {
        BigDecimal amount = BigDecimal.ZERO;
        for (TransactionSplit split : transaction.getSplits()) {
            Category category = split.getCategory();
            if (category != null && incomeCategories.contains(category.getId())) {
                amount = amount.add(split.getAmount());
            }
        }
        return amount;
    }

    /** The chosen categories and their subcategories. */
    private Set<Long> withSubcategories(Long userId, Set<Long> chosen) {
        Set<Long> all = new HashSet<>(chosen);
        if (chosen.isEmpty()) {
            return all;
        }
        for (Category category : categories.findByUserIdOrderBySortOrderAscNameAsc(userId)) {
            if (category.getParentId() != null && chosen.contains(category.getParentId())) {
                all.add(category.getId());
            }
        }
        return all;
    }

    /** A deadline on a weekend moves to the next Monday; public holidays are not known. */
    static LocalDate workday(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return date.plusDays(2);
        }
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return date.plusDays(1);
        }
        return date;
    }
}
