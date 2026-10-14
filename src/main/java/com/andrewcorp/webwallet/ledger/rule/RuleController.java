package com.andrewcorp.webwallet.ledger.rule;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.ledger.rule.RuleDtos.ApplyResponse;
import com.andrewcorp.webwallet.ledger.rule.RuleDtos.RuleRequest;
import com.andrewcorp.webwallet.ledger.rule.RuleDtos.RuleResponse;
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

import java.util.List;

@Tag(name = "Categorization rules")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/categorization-rules")
public class RuleController {

    private final RuleService ruleService;

    public RuleController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @Operation(summary = "List the rules, including the ones learned from manual category changes")
    @GetMapping
    public List<RuleResponse> list(@CurrentUserId Long userId) {
        return ruleService.list(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RuleResponse create(@CurrentUserId Long userId, @Valid @RequestBody RuleRequest request) {
        return ruleService.create(userId, request);
    }

    @PutMapping("/{id}")
    public RuleResponse update(@CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody RuleRequest request) {
        return ruleService.update(userId, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId Long userId, @PathVariable Long id) {
        ruleService.delete(userId, id);
    }

    @Operation(summary = "Put uncategorized expenses and incomes into categories by the rules")
    @PostMapping("/apply")
    public ApplyResponse apply(@CurrentUserId Long userId) {
        return new ApplyResponse(ruleService.applyToUncategorized(userId));
    }
}
