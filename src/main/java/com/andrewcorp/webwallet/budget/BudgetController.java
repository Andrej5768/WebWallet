package com.andrewcorp.webwallet.budget;

import com.andrewcorp.webwallet.budget.BudgetDtos.AssignRequest;
import com.andrewcorp.webwallet.budget.BudgetDtos.MonthResponse;
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
            summary = "Envelopes of the month: assigned, activity and available per expense category, and the money "
                    + "ready to assign")
    @GetMapping
    public MonthResponse month(
            @CurrentUserId Long userId,
            @Parameter(example = "2026-10") @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return budgetService.month(userId, month);
    }

    @Operation(summary = "Set the money assigned to an expense category for the month; 0 removes it")
    @PutMapping("/categories/{categoryId}")
    public MonthResponse assign(
            @CurrentUserId Long userId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @PathVariable Long categoryId,
            @Valid @RequestBody AssignRequest request) {
        return budgetService.assign(userId, month, categoryId, request.assigned());
    }
}
