package com.transactionexecution.api.infrastructure.persistence;

import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.model.TransactionStatus;
import com.transactionexecution.api.domain.model.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of the {@code transactions} table. Kept separate from the domain {@link Transaction}
 * so persistence concerns (versioning, audit timestamps, JPA constructors) do not leak into it.
 */
@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false, length = 64)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private TransactionType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(updatable = false)
    private String description;

    @Column(name = "idempotency_key", updatable = false, length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionStatus status;

    @Column(name = "provider_transaction_id", length = 64)
    private String providerTransactionId;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic locking; also tells Spring Data that a row with null version is new (insert, no select). */
    @Version
    private Long version;

    protected TransactionEntity() {
        // for JPA
    }

    static TransactionEntity from(Transaction transaction) {
        TransactionEntity entity = new TransactionEntity();
        entity.id = transaction.getId();
        entity.accountId = transaction.getAccountId();
        entity.type = transaction.getType();
        entity.amount = transaction.getAmount();
        entity.currency = transaction.getCurrency();
        entity.description = transaction.getDescription();
        entity.createdAt = transaction.getCreatedAt();
        entity.idempotencyKey = transaction.getIdempotencyKey();
        entity.applyOutcome(transaction);
        return entity;
    }

    /** Copies the fields that change after creation: status and provider outcome. */
    void applyOutcome(Transaction transaction) {
        this.status = transaction.getStatus();
        this.providerTransactionId = transaction.getProviderTransactionId();
        this.balanceAfter = transaction.getBalanceAfter();
        this.failureCode = transaction.getFailureCode();
        this.failureMessage = transaction.getFailureMessage();
    }

    Transaction toDomain() {
        return Transaction.restore(id, accountId, type, amount, currency, description, createdAt,
                idempotencyKey, status, providerTransactionId, balanceAfter, failureCode, failureMessage);
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    Long getVersion() {
        return version;
    }
}
