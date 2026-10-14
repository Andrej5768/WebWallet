package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionFilter;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    static Specification<Transaction> matching(Long userId, TransactionFilter filter) {
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
                // Left join: an inner join on the target account would drop everything but transfers
                Join<Object, Object> toAccount = root.join("toAccount", JoinType.LEFT);
                predicates.add(cb.or(
                        cb.equal(root.get("account").get("id"), filter.accountId()),
                        cb.equal(toAccount.get("id"), filter.accountId())));
            }
            if (filter.type() != null) {
                predicates.add(cb.equal(root.get("type"), filter.type()));
            }
            if (filter.payeeId() != null) {
                predicates.add(cb.equal(root.get("payee").get("id"), filter.payeeId()));
            }
            if (filter.categoryId() != null) {
                Subquery<Long> splits = query.subquery(Long.class);
                Root<TransactionSplit> split = splits.from(TransactionSplit.class);
                Join<Object, Object> category = split.join("category");
                splits.select(split.get("id")).where(
                        cb.equal(split.get("transaction"), root),
                        cb.or(cb.equal(category.get("id"), filter.categoryId()),
                                cb.equal(category.get("parentId"), filter.categoryId())));
                predicates.add(cb.exists(splits));
            }
            if (filter.tagId() != null) {
                predicates.add(cb.equal(root.join("tags").get("id"), filter.tagId()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
