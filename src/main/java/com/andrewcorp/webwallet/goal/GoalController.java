package com.andrewcorp.webwallet.goal;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.goal.GoalDtos.ContributionRequest;
import com.andrewcorp.webwallet.goal.GoalDtos.GoalRequest;
import com.andrewcorp.webwallet.goal.GoalDtos.GoalResponse;
import com.andrewcorp.webwallet.goal.GoalDtos.GoalsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Goals")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/goals")
public class GoalController {

    private final GoalService goalService;

    public GoalController(GoalService goalService) {
        this.goalService = goalService;
    }

    @Operation(summary = "Savings goals with their progress and the totals in the base currency")
    @GetMapping
    public GoalsResponse list(@CurrentUserId Long userId) {
        return goalService.list(userId);
    }

    @GetMapping("/{id}")
    public GoalResponse get(@CurrentUserId Long userId, @PathVariable Long id) {
        return goalService.get(userId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GoalResponse create(@CurrentUserId Long userId, @Valid @RequestBody GoalRequest request) {
        return goalService.create(userId, request);
    }

    @Operation(
            summary = "Replace a goal",
            description = "Omitted achieved and sortOrder keep their values. Moved to another account, the pace starts"
                    + " again from its balance.")
    @PutMapping("/{id}")
    public GoalResponse update(
            @CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody GoalRequest request) {
        return goalService.update(userId, id, request);
    }

    @Operation(summary = "Add to (or take from) the progress of a goal without an account")
    @PostMapping("/{id}/contributions")
    public GoalResponse contribute(
            @CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody ContributionRequest request) {
        return goalService.contribute(userId, id, request.amount());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId Long userId, @PathVariable Long id) {
        goalService.delete(userId, id);
    }
}
