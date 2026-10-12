package com.transactionexecution.api.infrastructure.persistence;

import com.transactionexecution.api.domain.model.TransactionFilter;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/** Builds the WHERE clause for a {@link TransactionFilter}; null fields are not filtered on. */
final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    static Specification<TransactionEntity> matching(TransactionFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.accountId() != null) {
                predicates.add(cb.equal(root.get("accountId"), filter.accountId()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.type() != null) {
                predicates.add(cb.equal(root.get("type"), filter.type()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
