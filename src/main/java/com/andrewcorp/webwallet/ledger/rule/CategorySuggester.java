package com.andrewcorp.webwallet.ledger.rule;

import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

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

    /** Suggestions for the many operations of one import, with the rules of the user loaded once. */
    public Session session(Long userId) {
        return new Session(userId, rules.findByUserIdOrderByIdAsc(userId));
    }

    /** Remembers the suggestions it made, as they become the history of the payee for later operations. */
    public final class Session {

        private final Long userId;
        private final List<CategorizationRule> userRules;
        private final Map<String, Optional<Category>> history = new HashMap<>();

        private Session(Long userId, List<CategorizationRule> userRules) {
            this.userId = userId;
            this.userRules = userRules;
        }

        /** @see CategorySuggester#suggest */
        public Category suggest(Payee payee, Short mcc, String bankCategory, CategoryKind kind) {
            Optional<Category> byRule = byRule(userRules, payee, mcc, bankCategory, kind);
            if (payee == null || payee.getId() == null) {
                return byRule.orElse(null);
            }
            String key = payee.getId() + ":" + kind;
            Optional<Category> category =
                    byRule.or(() -> history.computeIfAbsent(key, k -> byHistory(userId, payee, kind)));
            category.ifPresent(found -> history.put(key, category));
            return category.orElse(null);
        }
    }

    /** Only the rules, for categorizing existing transactions. */
    Optional<Category> byRule(
            List<CategorizationRule> userRules, Payee payee, Short mcc, String bankCategory, CategoryKind kind) {
        return userRules.stream()
                .filter(rule -> rule.getCategory().getKind() == kind
                        && !rule.getCategory().isArchived())
                .filter(rule -> rule.matches(payee, mcc, bankCategory))
                .max(Comparator.comparingInt(CategorizationRule::specificity).thenComparing(CategorizationRule::getId))
                .map(CategorizationRule::getCategory);
    }

    private Optional<Category> byHistory(Long userId, Payee payee, CategoryKind kind) {
        if (payee == null || payee.getId() == null) {
            return Optional.empty();
        }
        return transactions.findCategoriesUsedForPayee(userId, payee, kind, PageRequest.of(0, 1)).stream()
                .findFirst();
    }
}
