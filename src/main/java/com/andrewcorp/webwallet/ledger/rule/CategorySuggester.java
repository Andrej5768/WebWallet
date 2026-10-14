package com.andrewcorp.webwallet.ledger.rule;

import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Suggests a category for an imported operation: by the most specific matching categorization rule, otherwise by the
 * latest categorized transaction with the same payee.
 */
@Component
public class CategorySuggester {

    private final CategorizationRuleRepository rules;
    private final TransactionRepository transactions;

    public CategorySuggester(CategorizationRuleRepository rules, TransactionRepository transactions) {
        this.rules = rules;
        this.transactions = transactions;
    }

    /**
     * @param mcc          merchant category code of a card payment, if known
     * @param bankCategory classification of the operation by the bank, if known
     * @return null when nothing applies
     */
    public Category suggest(Long userId, Payee payee, Short mcc, String bankCategory, CategoryKind kind) {
        return byRule(rules.findByUserIdOrderByIdAsc(userId), payee, mcc, bankCategory, kind)
                .or(() -> byHistory(userId, payee, kind))
                .orElse(null);
    }

    /** Only the rules, for categorizing existing transactions. */
    Optional<Category> byRule(List<CategorizationRule> userRules, Payee payee, Short mcc, String bankCategory,
                              CategoryKind kind) {
        return userRules.stream()
                .filter(rule -> rule.getCategory().getKind() == kind && !rule.getCategory().isArchived())
                .filter(rule -> rule.matches(payee, mcc, bankCategory))
                .max(Comparator.comparingInt(CategorizationRule::specificity)
                        .thenComparing(CategorizationRule::getId))
                .map(CategorizationRule::getCategory);
    }

    private Optional<Category> byHistory(Long userId, Payee payee, CategoryKind kind) {
        if (payee == null || payee.getId() == null) {
            return Optional.empty();
        }
        return transactions.findCategoriesUsedForPayee(userId, payee, kind, PageRequest.of(0, 1)).stream().findFirst();
    }
}
