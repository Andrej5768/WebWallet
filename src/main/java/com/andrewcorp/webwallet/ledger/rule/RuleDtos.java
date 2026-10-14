package com.andrewcorp.webwallet.ledger.rule;

import com.andrewcorp.webwallet.ledger.NamedResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class RuleDtos {

    private RuleDtos() {
    }

    /**
     * At least one condition is required; every given condition must match.
     *
     * @param payeePattern case-insensitive part of the payee name
     * @param mcc          merchant category code, e.g. 5411 for supermarkets
     * @param bankCategory classification of the operation by the bank, e.g. "Супермаркети та продукти"
     */
    public record RuleRequest(
            @NotNull Long categoryId,
            Long payeeId,
            @Size(max = 200) String payeePattern,
            @Min(0) @Max(9999) Short mcc,
            @Size(max = 100) String bankCategory) {
    }

    /**
     * @param learned created automatically when the category of a transaction was changed manually
     */
    public record RuleResponse(
            Long id,
            Long categoryId,
            NamedResponse payee,
            String payeePattern,
            Short mcc,
            String bankCategory,
            boolean learned) {

        static RuleResponse from(CategorizationRule rule) {
            return new RuleResponse(rule.getId(), rule.getCategory().getId(),
                    rule.getPayee() == null ? null : new NamedResponse(rule.getPayee().getId(), rule.getPayee().getName()),
                    rule.getPayeePattern(), rule.getMcc(), rule.getBankCategory(), rule.isLearned());
        }
    }

    public record ApplyResponse(int categorized) {
    }
}
