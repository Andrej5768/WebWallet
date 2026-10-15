package com.andrewcorp.webwallet.imports;

import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.tag.Tag;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.Transaction.SplitData;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Changes the user made to the operations of an import preview, by operation key; operations without changes are
 * imported as previewed. The changes are validated before the import.
 */
public final class ImportReview {

    /** Imports every operation as it is. */
    public static final ImportReview NONE = new ImportReview(Map.of());

    private final Map<String, Edit> edits;

    public ImportReview(Map<String, Edit> edits) {
        this.edits = Map.copyOf(edits);
    }

    /**
     * The reviewed state of an operation.
     *
     * @param type            null keeps the detected type
     * @param transferAccount the other own account when an expense or income becomes a transfer
     * @param category        null for none
     * @param comment         null for none
     */
    public record Edit(
            boolean include,
            TransactionType type,
            Account transferAccount,
            Category category,
            Set<Tag> tags,
            String comment) {}

    /** Whether the user left the operation out of the import. */
    public boolean excludes(String key) {
        Edit edit = key == null ? null : edits.get(key);
        return edit != null && !edit.include();
    }

    /**
     * Applies the changes to the transaction an operation becomes. An expense becomes a transfer to the other
     * account, an income a transfer from it; a transfer becomes an expense of its source account or an income of its
     * target account.
     */
    public void apply(String key, Transaction transaction) {
        Edit edit = key == null ? null : edits.get(key);
        if (edit == null) {
            return;
        }
        TransactionType detected = transaction.getType();
        TransactionType type = edit.type() != null ? edit.type() : detected;
        if (type == TransactionType.TRANSFER && detected != TransactionType.TRANSFER) {
            Account other = edit.transferAccount();
            if (detected == TransactionType.EXPENSE) {
                transaction.setToAccount(other);
            } else {
                transaction.setToAccount(transaction.getAccount());
                transaction.setToBankId(transaction.getBankId());
                transaction.setBankId(null);
                transaction.setAccount(other);
            }
            transaction.setToAmount(transaction.getAmount());
            transaction.setOriginalAmount(null);
            transaction.setOriginalCurrency(null);
        } else if (type != TransactionType.TRANSFER && detected == TransactionType.TRANSFER) {
            if (type == TransactionType.INCOME) {
                transaction.setAccount(transaction.getToAccount());
                transaction.setAmount(transaction.getToAmount());
                transaction.setBankId(transaction.getToBankId());
            }
            transaction.setToAccount(null);
            transaction.setToAmount(null);
            transaction.setToBankId(null);
        }
        transaction.setType(type);
        transaction.replaceSplits(
                type == TransactionType.TRANSFER
                        ? List.of()
                        : List.of(new SplitData(edit.category(), transaction.getAmount(), null)));
        transaction.replaceTags(edit.tags());
        transaction.setComment(edit.comment());
    }
}
