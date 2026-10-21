package com.andrewcorp.webwallet.imports;

import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.ledger.transaction.TransferPairs;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Records what an import does with each operation, for a preview. The import runs in a transaction that is rolled
 * back, so the ids of the accounts and categories it creates are not reported.
 */
public final class ImportPreviewCollector {

    private final List<ImportOperation> operations = new ArrayList<>();
    private final Set<Long> createdAccounts = new HashSet<>();
    private final Set<Long> createdCategories = new HashSet<>();

    public void accountCreated(Account account) {
        createdAccounts.add(account.getId());
    }

    public void categoryCreated(Category category) {
        createdCategories.add(category.getId());
    }

    /**
     * @param transaction the transaction the operation becomes, or, for a duplicate, an unsaved one describing it
     */
    public void add(String key, Transaction transaction, boolean duplicate) {
        Category category = transaction.singleCategory();
        operations.add(new ImportOperation(
                key,
                transaction.getDate(),
                transaction.getPayee() == null ? null : transaction.getPayee().getName(),
                transaction.getAmount(),
                transaction.getAccount().getCurrency(),
                account(transaction.getAccount()),
                transaction.getToAccount() == null ? null : account(transaction.getToAccount()),
                transaction.getToAmount(),
                transaction.getType(),
                category == null || createdCategories.contains(category.getId()) ? null : category.getId(),
                category == null ? null : category.getName(),
                List.of(),
                transaction.getComment(),
                duplicate,
                transaction.getType() != TransactionType.TRANSFER && TransferPairs.transferLike(transaction),
                null));
    }

    /** In the order of the import: oldest first. */
    public List<ImportOperation> operations() {
        return operations;
    }

    private ImportOperation.ImportAccount account(Account account) {
        boolean created = account.getId() == null || createdAccounts.contains(account.getId());
        return new ImportOperation.ImportAccount(
                created ? null : account.getId(), account.getName(), account.getCurrency(), created);
    }
}
