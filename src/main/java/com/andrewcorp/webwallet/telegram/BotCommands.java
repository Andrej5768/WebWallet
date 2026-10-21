package com.andrewcorp.webwallet.telegram;

import static com.andrewcorp.webwallet.telegram.BotTexts.escape;

import com.andrewcorp.webwallet.budget.BudgetService;
import com.andrewcorp.webwallet.ledger.account.AccountService;
import com.andrewcorp.webwallet.ledger.account.dto.AccountResponse;
import com.andrewcorp.webwallet.ledger.account.dto.AccountSummaryResponse;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.planning.ForecastDtos.ForecastDay;
import com.andrewcorp.webwallet.planning.ForecastService;
import com.andrewcorp.webwallet.planning.RecurringDtos.OccurrenceResponse;
import com.andrewcorp.webwallet.planning.RecurringService;
import com.andrewcorp.webwallet.report.ReportDtos.CategorySpending;
import com.andrewcorp.webwallet.report.ReportDtos.SpendingResponse;
import com.andrewcorp.webwallet.report.ReportService;
import com.andrewcorp.webwallet.tax.FopTaxService;
import com.andrewcorp.webwallet.tax.TaxDtos.FopQuarter;
import com.andrewcorp.webwallet.tax.TaxDtos.FopReport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The reports of the commands /balance, /today, /month and /planned. */
@Component
class BotCommands {

    /** /planned looks this many days ahead, today included. */
    static final int PLANNED_DAYS = 30;

    private static final int MAX_PLANNED = 15;
    private static final int MAX_CATEGORIES = 6;

    private final AccountService accountService;
    private final ReportService reportService;
    private final BudgetService budgetService;
    private final RecurringService recurringService;
    private final ForecastService forecastService;
    private final FopTaxService fopTaxService;
    private final BotTexts texts;
    private final Clock clock;

    BotCommands(
            AccountService accountService,
            ReportService reportService,
            BudgetService budgetService,
            RecurringService recurringService,
            ForecastService forecastService,
            FopTaxService fopTaxService,
            BotTexts texts,
            Clock clock) {
        this.accountService = accountService;
        this.reportService = reportService;
        this.budgetService = budgetService;
        this.recurringService = recurringService;
        this.forecastService = forecastService;
        this.fopTaxService = fopTaxService;
        this.texts = texts;
        this.clock = clock;
    }

    /** The open accounts with their balances, and the net worth in the base currency. */
    String balance(Long userId, Locale locale) {
        List<AccountResponse> open = accountService.list(userId).stream()
                .filter(account -> !account.archived())
                .toList();
        if (open.isEmpty()) {
            return texts.get(locale, "bot.balance.none");
        }
        StringBuilder text = new StringBuilder(texts.get(locale, "bot.balance.title", BotTexts.date(today(), locale)));
        for (AccountResponse account : open) {
            line(text, escape(account.name()), BotTexts.money(account.balance(), account.currency(), locale));
        }
        AccountSummaryResponse summary = accountService.summary(userId);
        line(
                text,
                "<b>" + texts.get(locale, "bot.balance.total") + "</b>",
                "<b>" + BotTexts.money(summary.netWorth(), summary.currency(), locale) + "</b>");
        return text.toString();
    }

    /** The spending of today by category. */
    String today(Long userId, Locale locale) {
        LocalDate today = today();
        SpendingResponse spending = reportService.spending(userId, today, today);
        if (spending.total().signum() == 0) {
            return texts.get(locale, "bot.today.none");
        }
        StringBuilder text = new StringBuilder(texts.get(
                locale,
                "bot.today.title",
                BotTexts.date(today, locale) + ": "
                        + BotTexts.money(spending.total().negate(), spending.currency(), locale)));
        categories(text, spending, locale, false);
        return text.toString();
    }

    /** The spending of the month so far against its budget, by category, and the income. */
    String month(Long userId, Locale locale) {
        LocalDate today = today();
        YearMonth month = YearMonth.from(today);
        LocalDate first = month.atDay(1);
        SpendingResponse spending = reportService.spending(userId, first, today);
        BigDecimal budget = budgetService.month(userId, month).assigned();
        BigDecimal income = reportService.incomeExpense(userId, first, today).income();
        String currency = spending.currency();
        String days = today.getDayOfMonth() == 1 ? "1" : "1–" + today.getDayOfMonth();
        StringBuilder text =
                new StringBuilder(texts.get(locale, "bot.month.title", BotTexts.month(month, locale), days));
        String spent = BotTexts.wholeMoney(spending.total(), currency, locale);
        line(
                text,
                texts.get(locale, "bot.month.spent"),
                budget.signum() > 0
                        ? texts.get(locale, "bot.month.ofBudget", spent, BotTexts.wholeMoney(budget, currency, locale))
                        : spent);
        categories(text, spending, locale, true);
        line(text, texts.get(locale, "bot.month.income"), BotTexts.wholeMoney(income, currency, locale));
        return text.toString();
    }

    /**
     * The payments of recurring payments not yet made for the next 30 days (overdue ones too), the taxes of the sole
     * proprietor due then, and the day the forecast goes below zero.
     */
    String planned(Long userId, Locale locale) {
        LocalDate today = today();
        LocalDate last = today.plusDays(PLANNED_DAYS - 1);
        List<PlannedItem> items = new ArrayList<>();
        for (OccurrenceResponse occurrence : recurringService.pending(userId, last)) {
            if (occurrence.type() == TransactionType.TRANSFER) {
                continue;
            }
            BigDecimal amount = occurrence.type() == TransactionType.INCOME
                    ? occurrence.amount()
                    : occurrence.amount().negate();
            items.add(new PlannedItem(occurrence.date(), occurrence.name(), amount, occurrence.currency()));
        }
        items.addAll(taxes(userId, today, last, locale));
        items.sort(Comparator.comparing(PlannedItem::date).thenComparing(PlannedItem::name));

        StringBuilder text = new StringBuilder(
                texts.get(locale, "bot.planned.title", BotTexts.date(today, locale), BotTexts.date(last, locale)));
        if (items.isEmpty()) {
            text.append("\n\n").append(texts.get(locale, "bot.planned.none"));
        } else {
            text.append('\n');
            for (PlannedItem item : items.stream().limit(MAX_PLANNED).toList()) {
                String when = item.date().isBefore(today)
                        ? BotTexts.shortDate(item.date(), locale) + " (" + texts.get(locale, "bot.planned.overdue")
                                + ")"
                        : item.date().equals(today)
                                ? texts.get(locale, "bot.planned.today")
                                : BotTexts.shortDate(item.date(), locale);
                line(
                        text,
                        when + " · " + escape(item.name()),
                        BotTexts.signedMoney(item.amount(), item.currency(), locale));
            }
            if (items.size() > MAX_PLANNED) {
                text.append('\n').append(texts.get(locale, "bot.planned.more", items.size() - MAX_PLANNED));
            }
            totals(text, items, locale);
        }
        firstNegativeDay(userId, last)
                .ifPresent(day -> text.append("\n\n<b>")
                        .append(texts.get(locale, "bot.planned.forecast", BotTexts.date(day, locale)))
                        .append("</b>"));
        return text.toString();
    }

    private record PlannedItem(LocalDate date, String name, BigDecimal amount, String currency) {}

    /** Totals of the expenses and the incomes per currency. */
    private void totals(StringBuilder text, List<PlannedItem> items, Locale locale) {
        text.append('\n');
        for (String currency :
                items.stream().map(PlannedItem::currency).distinct().toList()) {
            BigDecimal expenses = sum(items, currency, -1);
            BigDecimal incomes = sum(items, currency, 1);
            if (expenses.signum() != 0) {
                line(
                        text,
                        "<b>" + texts.get(locale, "bot.planned.expenses") + "</b>",
                        "<b>" + BotTexts.money(expenses, currency, locale) + "</b>");
            }
            if (incomes.signum() != 0) {
                line(
                        text,
                        "<b>" + texts.get(locale, "bot.planned.incomes") + "</b>",
                        "<b>" + BotTexts.signedMoney(incomes, currency, locale) + "</b>");
            }
        }
    }

    private static BigDecimal sum(List<PlannedItem> items, String currency, int sign) {
        return items.stream()
                .filter(item ->
                        item.currency().equals(currency) && item.amount().signum() == sign)
                .map(PlannedItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The unpaid taxes of the sole proprietor due in the period; only for a user with a sole proprietor account. */
    private List<PlannedItem> taxes(Long userId, LocalDate from, LocalDate to, Locale locale) {
        List<PlannedItem> items = new ArrayList<>();
        // The quarters of the previous year are due early in the next one
        for (int year = from.getYear() - 1; year <= to.getYear(); year++) {
            FopReport report = fopTaxService.report(userId, year);
            if (report.fopAccountIds().isEmpty()) {
                return items;
            }
            for (FopQuarter quarter : report.quarters()) {
                if (quarter.paid()) {
                    continue;
                }
                BigDecimal tax = quarter.singleTax().add(quarter.military());
                if (tax.signum() > 0 && within(quarter.taxDue(), from, to)) {
                    items.add(new PlannedItem(
                            quarter.taxDue(),
                            texts.get(locale, "bot.planned.singleTax", roman(quarter.quarter())),
                            tax.negate(),
                            "UAH"));
                }
                if (quarter.esv().signum() > 0 && within(quarter.esvDue(), from, to)) {
                    items.add(new PlannedItem(
                            quarter.esvDue(),
                            texts.get(locale, "bot.planned.esv", roman(quarter.quarter())),
                            quarter.esv().negate(),
                            "UAH"));
                }
            }
        }
        return items;
    }

    private Optional<LocalDate> firstNegativeDay(Long userId, LocalDate to) {
        return forecastService.forecast(userId, to).days().stream()
                .filter(day -> !day.actual() && day.balance().signum() < 0)
                .map(ForecastDay::date)
                .findFirst();
    }

    /** The top-level categories, the largest first; with {@code collapse} the smaller ones become «Інше». */
    private void categories(StringBuilder text, SpendingResponse spending, Locale locale, boolean collapse) {
        List<CategorySpending> categories = spending.categories();
        int shown = collapse && categories.size() > MAX_CATEGORIES ? MAX_CATEGORIES - 1 : categories.size();
        for (CategorySpending category : categories.subList(0, shown)) {
            line(text, escape(category.name()), BotTexts.wholeMoney(category.amount(), spending.currency(), locale));
        }
        BigDecimal rest = categories.subList(shown, categories.size()).stream()
                .map(CategorySpending::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (rest.signum() != 0) {
            line(text, texts.get(locale, "bot.other"), BotTexts.wholeMoney(rest, spending.currency(), locale));
        }
        if (spending.uncategorized().signum() != 0) {
            line(
                    text,
                    texts.get(locale, "bot.uncategorized"),
                    BotTexts.wholeMoney(spending.uncategorized(), spending.currency(), locale));
        }
    }

    private static void line(StringBuilder text, String label, String value) {
        text.append('\n').append(label).append(" — ").append(value);
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    static String roman(int quarter) {
        return switch (quarter) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> "IV";
        };
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
