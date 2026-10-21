package com.andrewcorp.webwallet.notification;

import com.andrewcorp.webwallet.analytics.AnalyticsDtos.CategoryChange;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.CompareResponse;
import com.andrewcorp.webwallet.analytics.AnalyticsService;
import com.andrewcorp.webwallet.budget.BudgetService;
import com.andrewcorp.webwallet.report.ReportDtos.IncomeExpenseResponse;
import com.andrewcorp.webwallet.report.ReportService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The weekly and monthly spending reports, the same for Telegram and the email digest. A report not sent on its day,
 * e.g. when the messenger or the mail server failed, is still due for {@value #CATCH_UP_DAYS} days.
 */
@Component
public class SpendingReports {

    public static final int CATCH_UP_DAYS = 2;

    private static final int TOP_CHANGES = 2;
    private static final int CHART_CATEGORIES = 6;

    private final AnalyticsService analyticsService;
    private final ReportService reportService;
    private final BudgetService budgetService;

    public SpendingReports(
            AnalyticsService analyticsService, ReportService reportService, BudgetService budgetService) {
        this.analyticsService = analyticsService;
        this.reportService = reportService;
        this.budgetService = budgetService;
    }

    /**
     * The spending from the 1st to Sunday against the same days of the previous month.
     *
     * @param budgetUsed  percent of the budget of the month spent, null without a budget
     * @param monthPassed percent of the month passed by Sunday
     */
    public record Weekly(
            LocalDate monday,
            LocalDate from,
            LocalDate to,
            LocalDate previousFrom,
            LocalDate previousTo,
            CompareResponse compare,
            Integer budgetUsed,
            int monthPassed) {

        public List<CategoryChange> more() {
            return top(compare, 1);
        }

        public List<CategoryChange> less() {
            return top(compare, -1);
        }

        public List<SpendingCharts.Day> days() {
            return compare.days().stream()
                    .map(day -> new SpendingCharts.Day(day.day(), day.a(), day.b()))
                    .toList();
        }
    }

    /** The previous month against the month before it. */
    public record Monthly(
            YearMonth month,
            YearMonth before,
            CompareResponse compare,
            IncomeExpenseResponse current,
            IncomeExpenseResponse previous) {

        public List<CategoryChange> more() {
            return top(compare, 1);
        }

        public List<CategoryChange> less() {
            return top(compare, -1);
        }

        /**
         * The largest categories of the month with the month before; the smaller ones together as {@code other}.
         *
         * @param uncategorized the name of the spending without a category
         */
        public List<SpendingCharts.Bar> bars(String other, String uncategorized) {
            List<CategoryChange> categories = compare.categories().stream()
                    .sorted((a, b) -> b.a().compareTo(a.a()))
                    .toList();
            List<SpendingCharts.Bar> bars = new ArrayList<>();
            int shown = categories.size() > CHART_CATEGORIES ? CHART_CATEGORIES - 1 : categories.size();
            for (CategoryChange category : categories.subList(0, shown)) {
                String name = category.name() != null ? category.name() : uncategorized;
                bars.add(new SpendingCharts.Bar(name, category.a(), category.b()));
            }
            if (shown < categories.size()) {
                List<CategoryChange> rest = categories.subList(shown, categories.size());
                bars.add(new SpendingCharts.Bar(
                        other,
                        rest.stream().map(CategoryChange::a).reduce(BigDecimal.ZERO, BigDecimal::add),
                        rest.stream().map(CategoryChange::b).reduce(BigDecimal.ZERO, BigDecimal::add)));
            }
            return bars;
        }
    }

    /** On Mondays, except in the first week of a month, until Wednesday; empty without spending in both periods. */
    public Optional<Weekly> weekly(Long userId, LocalDate today) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (today.isAfter(monday.plusDays(CATCH_UP_DAYS)) || monday.getDayOfMonth() <= 7) {
            return Optional.empty();
        }
        LocalDate to = monday.minusDays(1);
        LocalDate from = to.withDayOfMonth(1);
        LocalDate previousFrom = from.minusMonths(1);
        LocalDate previousTo = previousFrom.withDayOfMonth(
                Math.min(to.getDayOfMonth(), YearMonth.from(previousFrom).lengthOfMonth()));
        CompareResponse compare = analyticsService.compare(userId, from, to, previousFrom, previousTo);
        if (compare.totalA().signum() == 0 && compare.totalB().signum() == 0) {
            return Optional.empty();
        }
        BigDecimal assigned = budgetService.month(userId, YearMonth.from(from)).assigned();
        Integer used = assigned.signum() > 0
                ? compare.totalA()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(assigned, 0, RoundingMode.HALF_UP)
                        .intValue()
                : null;
        int passed = to.getDayOfMonth() * 100 / YearMonth.from(from).lengthOfMonth();
        return Optional.of(new Weekly(monday, from, to, previousFrom, previousTo, compare, used, passed));
    }

    /** On the 1st, until the 3rd; empty for a month without income and spending. */
    public Optional<Monthly> monthly(Long userId, LocalDate today) {
        if (today.getDayOfMonth() > 1 + CATCH_UP_DAYS) {
            return Optional.empty();
        }
        YearMonth month = YearMonth.from(today).minusMonths(1);
        YearMonth before = month.minusMonths(1);
        CompareResponse compare = analyticsService.compare(
                userId, month.atDay(1), month.atEndOfMonth(), before.atDay(1), before.atEndOfMonth());
        IncomeExpenseResponse current = reportService.incomeExpense(userId, month.atDay(1), month.atEndOfMonth());
        IncomeExpenseResponse previous = reportService.incomeExpense(userId, before.atDay(1), before.atEndOfMonth());
        if (current.income().signum() == 0 && current.expense().signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(new Monthly(month, before, compare, current, previous));
    }

    /** The share of the change from {@code previous}, null when it was zero. */
    public static BigDecimal change(BigDecimal current, BigDecimal previous) {
        return previous.signum() == 0 ? null : current.subtract(previous).divide(previous, 4, RoundingMode.HALF_UP);
    }

    /** A share as whole percent without a sign, e.g. 12 for 0.1234. */
    public static int percent(BigDecimal share) {
        return share.abs()
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    /** The categories that grew ({@code sign} 1) or shrank (-1) most, two of them. */
    private static List<CategoryChange> top(CompareResponse compare, int sign) {
        return compare.categories().stream()
                .filter(change -> change.change().signum() == sign)
                .limit(TOP_CHANGES)
                .toList();
    }
}
