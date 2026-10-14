package com.andrewcorp.webwallet.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Reports in the base currency of the user. */
public final class ReportDtos {

    private ReportDtos() {
    }

    /**
     * @param categories    expense categories with spending, largest first; a parent includes its subcategories
     * @param uncategorized spending without a category
     */
    public record SpendingResponse(
            LocalDate from,
            LocalDate to,
            String currency,
            BigDecimal total,
            BigDecimal uncategorized,
            List<CategorySpending> categories) {
    }

    /** @param share part of the total spending, 0..1 */
    public record CategorySpending(Long categoryId, String name, BigDecimal amount, BigDecimal share,
                                   List<CategorySpending> children) {
    }

    public record PayeesResponse(LocalDate from, LocalDate to, String currency, List<PayeeSpending> payees) {
    }

    public record PayeeSpending(Long payeeId, String name, BigDecimal amount, int transactions) {
    }

    /** @param months every month of the period, also months without operations */
    public record IncomeExpenseResponse(
            LocalDate from,
            LocalDate to,
            String currency,
            BigDecimal income,
            BigDecimal expense,
            BigDecimal net,
            BigDecimal savingsRate,
            List<MonthIncomeExpense> months) {
    }

    /** @param savingsRate part of the income that was not spent, null without income */
    public record MonthIncomeExpense(YearMonth month, BigDecimal income, BigDecimal expense, BigDecimal net,
                                     BigDecimal savingsRate) {
    }

    public record NetWorthResponse(String currency, List<MonthNetWorth> months) {
    }

    /**
     * Balances at the end of the month, converted at the rates of that day.
     *
     * @param assets      sum of positive balances
     * @param liabilities sum of negative balances (credit card and loan debt), negative
     */
    public record MonthNetWorth(YearMonth month, BigDecimal assets, BigDecimal liabilities, BigDecimal netWorth) {
    }
}
