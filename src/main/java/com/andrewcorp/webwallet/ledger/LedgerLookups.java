package com.andrewcorp.webwallet.ledger;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.payee.PayeeRepository;
import com.andrewcorp.webwallet.ledger.payee.PayeeService;
import org.springframework.stereotype.Component;

/**
 * The references a transaction (or a recurring payment, which creates transactions) may have: accounts, categories
 * and payees of the user, checked by the same rules everywhere. A missing one is a validation error of the field.
 */
@Component
public class LedgerLookups {

    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final PayeeRepository payees;
    private final PayeeService payeeService;

    public LedgerLookups(
            AccountRepository accounts,
            CategoryRepository categories,
            PayeeRepository payees,
            PayeeService payeeService) {
        this.accounts = accounts;
        this.categories = categories;
        this.payees = payees;
        this.payeeService = payeeService;
    }

    public Account account(Long userId, Long id, String field) {
        return accounts.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.invalid(field, "error.notFound"));
    }

    /** Null id means uncategorized. An income may have an expense category: it is a refund of that spending. */
    public Category category(Long userId, Long id, CategoryKind kind, String field) {
        if (id == null) {
            return null;
        }
        Category category = categories
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.invalid(field, "error.notFound"));
        if (!fits(category, kind)) {
            throw ApiException.invalid(field, "error.category.kind");
        }
        return category;
    }

    /** Whether the category may be used for an operation of the kind. */
    public static boolean fits(Category category, CategoryKind kind) {
        boolean refund = kind == CategoryKind.INCOME && category.getKind() == CategoryKind.EXPENSE;
        return category.getKind() == kind || refund;
    }

    /** An existing payee by id, else one found or created by name, else none. */
    public Payee payee(Long userId, Long payeeId, String payeeName) {
        if (payeeId != null) {
            return payees.findByIdAndUserId(payeeId, userId)
                    .orElseThrow(() -> ApiException.invalid("payeeId", "error.notFound"));
        }
        if (payeeName != null && !payeeName.isBlank()) {
            return payeeService.findOrCreate(userId, payeeName);
        }
        return null;
    }
}
