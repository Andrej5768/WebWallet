package com.andrewcorp.webwallet.budget;

import com.andrewcorp.webwallet.budget.BudgetDtos.AssignManyRequest;
import com.andrewcorp.webwallet.budget.BudgetDtos.AssignRequest;
import com.andrewcorp.webwallet.budget.BudgetDtos.MonthResponse;
import com.andrewcorp.webwallet.budget.BudgetDtos.MoveRequest;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.YearMonth;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Budget")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/budget/{month}")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @Operation(
            summary = "Envelopes of the month: assigned, activity and available per expense category, the money "
                    + "ready to assign, and the planned and received income per income category")
    @GetMapping
    public MonthResponse month(
            @CurrentUserId Long userId,
            @Parameter(example = "2026-10") @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return budgetService.month(userId, month);
    }

    @Operation(
            summary = "Set the plan of a category for the month; 0 removes it",
            description = "For an expense category the money of its envelope, for an income category the income"
                    + " expected.")
    @PutMapping("/categories/{categoryId}")
    public MonthResponse assign(
            @CurrentUserId Long userId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @PathVariable Long categoryId,
            @Valid @RequestBody AssignRequest request) {
        return budgetService.assign(userId, month, categoryId, request.assigned());
    }

    @Operation(
            summary = "Set the plans of several categories of the month at once",
            description = "All plans are saved or none. Returns the month like GET.")
    @PutMapping("/categories")
    public MonthResponse assignMany(
            @CurrentUserId Long userId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @Valid @RequestBody AssignManyRequest request) {
        return budgetService.assignMany(userId, month, request.plans());
    }

    @Operation(
            summary = "Move money from the own envelope of one expense category to another in one step",
            description = "Lowers the own assignment of fromCategoryId and raises that of toCategoryId by the amount;"
                    + " both change or neither. Returns the month like GET.")
    @PostMapping("/move")
    public MonthResponse move(
            @CurrentUserId Long userId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @Valid @RequestBody MoveRequest request) {
        return budgetService.move(userId, month, request);
    }

    @Operation(
            summary = "Copy the plans of the previous month to the categories without one this month",
            description = "Every expense or income category whose own plan this month is zero gets its own plan of"
                    + " the previous month; categories already planned and archived categories are left as they are."
                    + " Returns the month like GET.")
    @PostMapping("/copy-previous")
    public MonthResponse copyPrevious(
            @CurrentUserId Long userId, @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return budgetService.copyPrevious(userId, month);
    }
}
