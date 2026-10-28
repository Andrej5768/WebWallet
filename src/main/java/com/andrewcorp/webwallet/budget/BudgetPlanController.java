package com.andrewcorp.webwallet.budget;

import com.andrewcorp.webwallet.budget.BudgetDtos.BudgetPlanResponse;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Budget")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/budget")
public class BudgetPlanController {

    private final BudgetPlanService planService;

    public BudgetPlanController(BudgetPlanService planService) {
        this.planService = planService;
    }

    @Operation(
            summary = "Plan and actual income and spending per category for a range of months",
            description = "At most 36 months. Each row also carries the average actual value of the three full months"
                    + " before the current one.")
    @GetMapping
    public BudgetPlanResponse plan(
            @CurrentUserId Long userId,
            @Parameter(example = "2025-10") @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth from,
            @Parameter(example = "2027-10") @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth to) {
        return planService.plan(userId, from, to);
    }
}
