package com.andrewcorp.webwallet.imports;

import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.tag.Tag;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.Transaction.SplitData;
import com.andrewcorp.webwallet.ledger.transaction.TransactionSplit;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    /** Keys of operations that are a half of a pair to merge, and the transactions they became. */
    private final Set<String> paired = new HashSet<>();

    private final Map<String, Transaction> saved = new HashMap<>();

    public ImportReview(Map<String, Edit> edits) {
        this.edits = Map.copyOf(edits);
        edits.forEach((key, edit) -> {
            if (edit.include() && (edit.pairKey() != null || edit.pairTransaction() != null)) {
                paired.add(key);
                if (edit.pairKey() != null) {
                    paired.add(edit.pairKey());
                }
            }
        });
    }

    /**
     * The reviewed state of an operation.
     *
     * @param type            null keeps the detected type
     * @param transferAccount the other own account when an expense or income becomes a transfer
     * @param category        null for none; ignored when {@code keepCategory} is set
     * @param keepCategory    keeps the category the import suggests, also one it creates
     * @param comment         null for none
     * @param pairKey         an operation of the same import to merge with into one transfer
     * @param pairTransaction a transaction of the ledger to merge with into one transfer
     */
    public record Edit(
            boolean include,
            TransactionType type,
            Account transferAccount,
            Category category,
            boolean keepCategory,
            Set<Tag> tags,
            String comment,
            String pairKey,
            Transaction pairTransaction) {

        public Edit(
                boolean include,
                TransactionType type,
                Account transferAccount,
                Category category,
                boolean keepCategory,
                Set<Tag> tags,
                String comment) {
            this(include, type, transferAccount, category, keepCategory, tags, comment, null, null);
        }
    }

    /** An expense and an income to merge into one transfer once the import saved them. */
    public record Pair(Transaction expense, Transaction income) {}

    /** Tells the transaction an operation became, for the pairs to merge. */
    public void saved(String key, Transaction transaction) {
        if (key != null && paired.contains(key)) {
            saved.put(key, transaction);
        }
    }

    /**
     * The pairs the user chose, of operations the import saved; an operation recognized as already in the ledger is
     * left as it is. A pair chosen on both of its operations is merged once.
     */
    public List<Pair> pairs() {
        List<Pair> pairs = new ArrayList<>();
        Set<String> done = new HashSet<>();
        edits.forEach((key, edit) -> {
            Transaction first = saved.get(key);
            if (first == null || !edit.include()) {
                return;
            }
            Transaction second = null;
            if (edit.pairKey() != null) {
                String pair =
                        key.compareTo(edit.pairKey()) < 0 ? key + "|" + edit.pairKey() : edit.pairKey() + "|" + key;
                if (!done.add(pair)) {
                    return;
                }
                second = saved.get(edit.pairKey());
            } else if (edit.pairTransaction() != null) {
                second = edit.pairTransaction();
            }
            if (second == null) {
                return;
            }
            boolean firstIsExpense = first.getType() == TransactionType.EXPENSE;
            pairs.add(firstIsExpense ? new Pair(first, second) : new Pair(second, first));
        });
        return pairs;
    }

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
        Category category = edit.keepCategory() ? suggestedCategory(transaction) : edit.category();
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
                        : List.of(new SplitData(category, transaction.getAmount(), null)));
        transaction.replaceTags(edit.tags());
        transaction.setComment(edit.comment());
    }

    /** The category the import gave the transaction; none for a transfer. */
    private static Category suggestedCategory(Transaction transaction) {
        List<TransactionSplit> splits = transaction.getSplits();
        return splits.size() == 1 ? splits.getFirst().getCategory() : null;
    }
}
