package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Suggests a category for an imported transaction: the category of the latest categorized transaction with the same
 * payee, so that a manual correction is repeated for later operations.
 */
@Component
public class CategorySuggester {

    private final TransactionRepository transactions;

    public CategorySuggester(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    /** Null when there is nothing to learn from. */
    public Category suggest(Long userId, Payee payee, CategoryKind kind) {
        if (payee == null || payee.getId() == null) {
            return null;
        }
        return transactions.findCategoriesUsedForPayee(userId, payee, kind, PageRequest.of(0, 1)).stream()
                .findFirst()
                .orElse(null);
    }
}
