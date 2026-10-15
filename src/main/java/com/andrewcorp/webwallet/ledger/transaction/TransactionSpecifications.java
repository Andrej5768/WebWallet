package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.data.jpa.domain.Specification;

final class TransactionSpecifications {

    /** An amount as people type it: digits with an optional fraction after a point or a comma. */
    private static final Pattern AMOUNT = Pattern.compile("\\d+([.,]\\d+)?");
    /** Spaces, including the non-breaking ones, that group the thousands of an amount, e.g. "1 284,50". */
    private static final Pattern GROUPING = Pattern.compile("[\\s\\u00A0\\u202F]");

    private static final char LIKE_ESCAPE = '!';

    private TransactionSpecifications() {}

    /** @param categoryIds the filtered category and its subcategories, or null when not filtered by category */
    static Specification<Transaction> matching(Long userId, TransactionFilter filter, Collection<Long> categoryIds) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("date"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("date"), filter.to()));
            }
            if (filter.accountId() != null) {
                // The foreign key columns are compared directly, without joining the accounts
                predicates.add(cb.or(
                        cb.equal(root.get("account").get("id"), filter.accountId()),
                        cb.equal(root.get("toAccount").get("id"), filter.accountId())));
            }
            if (filter.type() != null) {
                predicates.add(cb.equal(root.get("type"), filter.type()));
            }
            if (filter.payeeId() != null) {
                predicates.add(cb.equal(root.get("payee").get("id"), filter.payeeId()));
            }
            if (categoryIds != null) {
                // The foreign key of the split is compared directly, so the index on it can drive the query
                Subquery<Long> splits = query.subquery(Long.class);
                Root<TransactionSplit> split = splits.from(TransactionSplit.class);
                splits.select(split.get("id"))
                        .where(
                                cb.equal(split.get("transaction"), root),
                                split.get("category").get("id").in(categoryIds));
                predicates.add(cb.exists(splits));
            }
            if (filter.tagId() != null) {
                predicates.add(cb.equal(root.join("tags").get("id"), filter.tagId()));
            }
            if (filter.q() != null && !filter.q().isBlank()) {
                predicates.add(search(filter.q().trim(), root, query, cb));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Predicate search(String text, Root<Transaction> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        String pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
        Subquery<Long> splits = query.subquery(Long.class);
        Root<TransactionSplit> split = splits.from(TransactionSplit.class);
        splits.select(split.get("id"))
                .where(
                        cb.equal(split.get("transaction"), root),
                        cb.like(cb.lower(split.get("comment")), pattern, LIKE_ESCAPE));
        List<Predicate> matches = new ArrayList<>(List.of(
                cb.like(cb.lower(root.join("payee", JoinType.LEFT).get("name")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.get("comment")), pattern, LIKE_ESCAPE),
                cb.exists(splits)));
        BigDecimal amount = parseAmount(text);
        if (amount != null) {
            // Amounts are stored positive; a transfer also matches by the received amount
            matches.add(cb.equal(root.get("amount"), amount));
            matches.add(cb.equal(root.get("toAmount"), amount));
        }
        return cb.or(matches.toArray(Predicate[]::new));
    }

    /** The text as an amount, e.g. "1284.50", "1284,50" or "1 284,50", ignoring a sign; null if it is not one. */
    static BigDecimal parseAmount(String text) {
        String compact = GROUPING.matcher(text).replaceAll("");
        if (compact.startsWith("-") || compact.startsWith("+")) {
            compact = compact.substring(1);
        }
        if (!AMOUNT.matcher(compact).matches()) {
            return null;
        }
        return new BigDecimal(compact.replace(',', '.'));
    }

    /** Makes the wildcards of LIKE match themselves. */
    private static String escapeLike(String text) {
        String escape = String.valueOf(LIKE_ESCAPE);
        return text.replace(escape, escape + escape).replace("%", escape + "%").replace("_", escape + "_");
    }
}
