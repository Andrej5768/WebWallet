package com.andrewcorp.webwallet.planning;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.common.web.Periods;
import com.andrewcorp.webwallet.planning.ForecastDtos.ForecastResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Forecast")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/forecast")
public class ForecastController {

    private static final int MAX_DAYS = 366;

    private final ForecastService forecastService;
    private final Clock clock;

    public ForecastController(ForecastService forecastService, Clock clock) {
        this.forecastService = forecastService;
        this.clock = clock;
    }

    @Operation(
            summary = "Balance of cash and cards from the first of the month to the date: actual, then forecast",
            description = "The forecast takes away the everyday spending (the average of the last 90 days without"
                    + " recurring payments) and adds the pending occurrences of recurring payments. By default to the"
                    + " end of the current month, at most " + MAX_DAYS + " days ahead.")
    @GetMapping
    public ForecastResponse forecast(
            @CurrentUserId Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate end = to != null ? to : today.withDayOfMonth(today.lengthOfMonth());
        if (end.isBefore(today)) {
            throw ApiException.invalid("to", "error.forecast.past");
        }
        Periods.checkDays(today, end, MAX_DAYS + 1, "to");
        return forecastService.forecast(userId, end);
    }
}
