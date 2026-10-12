package com.transactionexecution.api.infrastructure.persistence;

import com.transactionexecution.api.domain.exception.DuplicateIdempotencyKeyException;
import com.transactionexecution.api.domain.model.PageQuery;
import com.transactionexecution.api.domain.model.PageResult;
import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.model.TransactionFilter;
import com.transactionexecution.api.domain.port.TransactionReadRepository;
import com.transactionexecution.api.domain.port.TransactionWriteRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing both the {@link TransactionWriteRepository} and the
 * {@link TransactionReadRepository} ports with Spring Data JPA, on the same PostgreSQL database.
 * The read side can move to its own adapter (e.g. a read replica) without changing the ports.
 */
@Repository
class JpaTransactionRepository implements TransactionWriteRepository, TransactionReadRepository {

    /** Newest first; id breaks ties so pages stay stable when timestamps are equal. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /** Unique constraint on {@code idempotency_key}, see V1__create_transactions_table.sql. */
    static final String IDEMPOTENCY_KEY_CONSTRAINT = "uk_transactions_idempotency_key";

    private final TransactionJpaRepository jpaRepository;

    JpaTransactionRepository(TransactionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    /**
     * Inserts a new transaction or updates the outcome of an existing one. The lookup keeps the
     * entity's version, so concurrent updates of the same row fail instead of overwriting each other.
     *
     * <p>Inserts are flushed immediately so a clash on the idempotency key's unique constraint
     * surfaces here, as a {@link DuplicateIdempotencyKeyException}, and not later at commit.
     */
    @Override
    @Transactional
    public Transaction save(Transaction transaction) {
        Optional<TransactionEntity> existing = jpaRepository.findById(transaction.getId());
        if (existing.isPresent()) {
            existing.get().applyOutcome(transaction);
            return jpaRepository.save(existing.get()).toDomain();
        }
        try {
            return jpaRepository.saveAndFlush(TransactionEntity.from(transaction)).toDomain();
        } catch (DataIntegrityViolationException e) {
            if (violates(e, IDEMPOTENCY_KEY_CONSTRAINT)) {
                throw new DuplicateIdempotencyKeyException(transaction.getIdempotencyKey(), e);
            }
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Transaction> findById(UUID id) {
        return jpaRepository.findById(id).map(TransactionEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Transaction> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey).map(TransactionEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Transaction> search(TransactionFilter filter, PageQuery pageQuery) {
        Page<TransactionEntity> page = jpaRepository.findAll(TransactionSpecifications.matching(filter),
                PageRequest.of(pageQuery.page(), pageQuery.size(), NEWEST_FIRST));
        return new PageResult<>(page.getContent().stream().map(TransactionEntity::toDomain).toList(),
                pageQuery.page(), pageQuery.size(), page.getTotalElements());
    }

    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraint.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
