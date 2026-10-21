package com.andrewcorp.webwallet.telegram;

import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.payee.PayeeRepository;
import com.andrewcorp.webwallet.ledger.rule.CategorySuggester;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * The category of an operation written to the bot, from its description: a payee of that name or the categorization
 * rules, then the latest transaction with the same description, then a category of that name («таксі» → Таксі).
 */
@Component
class CategoryGuesser {

    private static final int MIN_STEM = 4;

    private final PayeeRepository payees;
    private final CategorySuggester suggester;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;

    CategoryGuesser(
            PayeeRepository payees,
            CategorySuggester suggester,
            TransactionRepository transactions,
            CategoryRepository categories) {
        this.payees = payees;
        this.suggester = suggester;
        this.transactions = transactions;
        this.categories = categories;
    }

    /** @param payee the existing payee of that name, or null */
    record Guess(Category category, Payee payee) {}

    Guess guess(Long userId, String description, CategoryKind kind) {
        if (description.isBlank()) {
            return new Guess(null, null);
        }
        Payee payee = payees.findByUserIdAndNameIgnoreCase(userId, description).orElse(null);
        // A rule «payee contains …» applies to the description even without such a payee
        Payee named = payee != null ? payee : new Payee(userId, description);
        Category category = suggester.session(userId).suggest(named, null, null, kind);
        if (category == null) {
            category =
                    transactions.findCategoriesUsedForComment(userId, description, kind, PageRequest.of(0, 1)).stream()
                            .findFirst()
                            .orElse(null);
        }
        if (category == null) {
            category = byName(userId, description, kind).orElse(null);
        }
        return new Guess(category, payee);
    }

    /** The category named like the description or its first word; a subcategory beats a group. */
    private Optional<Category> byName(Long userId, String description, CategoryKind kind) {
        String whole = normalize(description);
        String first = normalize(description.split("\\s+")[0]);
        String stem = first.length() > MIN_STEM ? first.substring(0, first.length() - 1) : first;
        List<Category> candidates = categories.findByUserIdOrderBySortOrderAscNameAsc(userId).stream()
                .filter(category -> category.getKind() == kind && !category.isArchived())
                .toList();
        Comparator<Category> subcategoriesFirst = Comparator.comparing(category -> category.getParentId() == null);
        Optional<Category> exact = candidates.stream()
                .filter(category -> normalize(category.getName()).equals(whole))
                .min(subcategoriesFirst);
        if (exact.isPresent() || stem.length() < MIN_STEM) {
            return exact;
        }
        return candidates.stream()
                .filter(category -> List.of(category.getName().split("[^\\p{L}\\p{N}]+")).stream()
                        .anyMatch(word -> normalize(word).startsWith(stem)))
                .min(subcategoriesFirst);
    }

    /** Compared like account names, so that a Latin «i» typed among Cyrillic letters still matches. */
    private static String normalize(String text) {
        return AccountNames.normalize(text);
    }
}
