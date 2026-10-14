package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionFilter;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

final class TransactionSpecifications {

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
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
