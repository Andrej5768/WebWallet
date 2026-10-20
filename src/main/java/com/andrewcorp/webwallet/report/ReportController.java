package com.andrewcorp.webwallet.report;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.common.web.Periods;
import com.andrewcorp.webwallet.report.ReportDtos.IncomeExpenseResponse;
import com.andrewcorp.webwallet.report.ReportDtos.NetWorthResponse;
import com.andrewcorp.webwallet.report.ReportDtos.PayeesResponse;
import com.andrewcorp.webwallet.report.ReportDtos.SpendingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Without a period, reports cover the last 12 months including the current one.
 */
@Tag(name = "Reports")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private static final int MAX_YEARS = 20;

    private final ReportService reportService;
    private final Clock clock;

    public ReportController(ReportService reportService, Clock clock) {
        this.reportService = reportService;
        this.clock = clock;
    }

    @Operation(summary = "Spending by category")
    @GetMapping("/spending")
    public SpendingResponse spending(
            @CurrentUserId Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Period period = period(from, to);
        return reportService.spending(userId, period.from(), period.to());
    }

    @Operation(summary = "Spending by payee, largest first")
    @GetMapping("/payees")
    public PayeesResponse payees(
            @CurrentUserId Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        Period period = period(from, to);
        return reportService.payees(userId, period.from(), period.to(), limit);
    }

    @Operation(summary = "Income and expenses per month and the savings rate")
    @GetMapping("/income-expense")
    public IncomeExpenseResponse incomeExpense(
            @CurrentUserId Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Period period = period(from, to);
        return reportService.incomeExpense(userId, period.from(), period.to());
    }

    @Operation(summary = "Net worth at the end of every month: assets, liabilities and their sum")
    @GetMapping("/net-worth")
    public NetWorthResponse netWorth(
            @CurrentUserId Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Period period = period(from, to);
        return reportService.netWorth(userId, period.from(), period.to());
    }

    private Period period(LocalDate from, LocalDate to) {
        YearMonth current = YearMonth.now(clock);
        LocalDate end = to != null ? to : current.atEndOfMonth();
        LocalDate start = from != null ? from : current.minusMonths(11).atDay(1);
        Periods.checkYears(start, end, MAX_YEARS, "from");
        return new Period(start, end);
    }

    private record Period(LocalDate from, LocalDate to) {}
}
