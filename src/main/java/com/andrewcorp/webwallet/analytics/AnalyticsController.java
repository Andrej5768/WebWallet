package com.andrewcorp.webwallet.analytics;

import com.andrewcorp.webwallet.analytics.AnalyticsDtos.CompareResponse;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.NeedsWantsResponse;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.PlanResponse;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.SavingsResponse;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.TrendsResponse;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Analytics")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private static final int MAX_COMPARE_DAYS = 366;
    private static final int MAX_TREND_MONTHS = 36;
    private static final int MAX_YEARS = 20;

    private final AnalyticsService analyticsService;
    private final Clock clock;

    public AnalyticsController(AnalyticsService analyticsService, Clock clock) {
        this.analyticsService = analyticsService;
        this.clock = clock;
    }

    @Operation(
            summary = "Plan vs actual per envelope with a forecast for the end of the month",
            description = "The plan is the money assigned to the envelopes, the actual value the spending in them."
                    + " For the current month the forecast continues the spending at its pace so far to the end of"
                    + " the month and adds the planned payments still due.")
    @GetMapping("/plan/{month}")
    public PlanResponse plan(
            @CurrentUserId Long userId,
            @Parameter(example = "2026-10") @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return analyticsService.plan(userId, month);
    }

    @Operation(
            summary = "Compare the spending of two periods",
            description = "Cumulative spending by day from the start of each period and the change per top-level"
                    + " category. Each period is at most 366 days.")
    @GetMapping("/compare")
    public CompareResponse compare(
            @CurrentUserId Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromA,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toA,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromB,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toB) {
        checkComparePeriod(fromA, toA, "fromA");
        checkComparePeriod(fromB, toB, "fromB");
        return analyticsService.compare(userId, fromA, toA, fromB, toB);
    }

    @Operation(
            summary = "Monthly spending per top-level category, averages and anomalies",
            description = "Without a range, the last 12 complete months. At most 36 months.")
    @GetMapping("/trends")
    public TrendsResponse trends(
            @CurrentUserId Long userId,
            @Parameter(example = "2025-10") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM")
                    YearMonth from,
            @Parameter(example = "2026-09") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM")
                    YearMonth to) {
        YearMonth end = to != null ? to : YearMonth.now(clock).minusMonths(1);
        YearMonth start = from != null ? from : end.minusMonths(11);
        checkMonths(start, end, MAX_TREND_MONTHS, "error.analytics.monthsTooLong");
        return analyticsService.trends(userId, start, end);
    }

    @Operation(
            summary = "Money saved per month, the savings rate and where the saved money was moved",
            description = "Without a range, the last 12 months including the current one.")
    @GetMapping("/savings")
    public SavingsResponse savings(
            @CurrentUserId Long userId,
            @Parameter(example = "2025-11") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM")
                    YearMonth from,
            @Parameter(example = "2026-10") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM")
                    YearMonth to,
            @Parameter(description = "Wanted savings rate, 0..1")
                    @RequestParam(defaultValue = "0.2")
                    @DecimalMin("0") @DecimalMax("1") BigDecimal target) {
        YearMonth end = to != null ? to : YearMonth.now(clock);
        YearMonth start = from != null ? from : end.minusMonths(11);
        checkMonths(start, end, MAX_YEARS * 12, "error.report.periodTooLong");
        return analyticsService.savings(userId, start, end, target);
    }

    @Operation(
            summary = "50/30/20: needs, wants and savings as parts of the income",
            description = "By the needs / wants flag of the expense categories. Without a period, the current month.")
    @GetMapping("/needs-wants")
    public NeedsWantsResponse needsWants(
            @CurrentUserId Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        YearMonth current = YearMonth.now(clock);
        LocalDate end = to != null ? to : current.atEndOfMonth();
        LocalDate start = from != null ? from : current.atDay(1);
        if (start.isAfter(end)) {
            throw ApiException.invalid("from", "error.report.period");
        }
        if (start.isBefore(end.minusYears(MAX_YEARS))) {
            throw ApiException.invalid("from", "error.report.periodTooLong");
        }
        return analyticsService.needsWants(userId, start, end);
    }

    private static void checkComparePeriod(LocalDate from, LocalDate to, String field) {
        if (from.isAfter(to)) {
            throw ApiException.invalid(field, "error.report.period");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_COMPARE_DAYS) {
            throw ApiException.invalid(field, "error.analytics.compareTooLong");
        }
    }

    private static void checkMonths(YearMonth from, YearMonth to, int max, String tooLong) {
        if (from.isAfter(to)) {
            throw ApiException.invalid("from", "error.report.period");
        }
        if (ChronoUnit.MONTHS.between(from, to) + 1 > max) {
            throw ApiException.invalid("from", tooLong);
        }
    }
}
