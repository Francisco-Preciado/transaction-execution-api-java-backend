package com.transactionexecution.api.domain.model;

import com.transactionexecution.api.domain.exception.InvalidTransactionStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a financial transaction executed against the external provider.
 *
 * <p>The immutable request data (account, type, amount, currency, description) is fixed at
 * creation. Only the outcome fields change, and only once: a transaction is created as
 * {@link TransactionStatus#PENDING} and is then marked executed, rejected or failed.
 *
 * <p>Business rules (minimum amount, debit limit, currency) are not enforced here; they are
 * validated before a transaction is created. This class only guards its own invariants.
 */
public final class Transaction {

    private final UUID id;
    private final String accountId;
    private final TransactionType type;
    private final BigDecimal amount;
    private final String currency;
    private final String description;
    private final Instant createdAt;
    private final String idempotencyKey;

    private TransactionStatus status;
    private String providerTransactionId;
    private BigDecimal balanceAfter;
    private String failureCode;
    private String failureMessage;

    private Transaction(UUID id, String accountId, TransactionType type, BigDecimal amount, String currency,
                        String description, Instant createdAt, String idempotencyKey, TransactionStatus status,
                        String providerTransactionId, BigDecimal balanceAfter,
                        String failureCode, String failureMessage) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.accountId = requireNotBlank(accountId, "accountId");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.currency = requireNotBlank(currency, "currency");
        this.description = description;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.idempotencyKey = idempotencyKey;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.providerTransactionId = providerTransactionId;
        this.balanceAfter = balanceAfter;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    /** Creates a new transaction without an idempotency key, ready to be sent to the provider. */
    public static Transaction create(String accountId, TransactionType type, BigDecimal amount,
                                     String currency, String description, Instant createdAt) {
        return new Transaction(UUID.randomUUID(), accountId, type, amount, currency, description, createdAt,
                null, TransactionStatus.PENDING, null, null, null, null);
    }

    /**
     * Creates a new transaction from a request that already passed the business rules.
     *
     * @param idempotencyKey the client's key for this request, or {@code null} if it sent none
     */
    public static Transaction create(TransactionRequest request, String idempotencyKey, Instant createdAt) {
        return new Transaction(UUID.randomUUID(), request.accountId(), request.type(), request.amount(),
                request.currency(), request.description(), createdAt, idempotencyKey, TransactionStatus.PENDING,
                null, null, null, null);
    }

    /** Rebuilds a transaction from storage. Intended for persistence adapters only. */
    public static Transaction restore(UUID id, String accountId, TransactionType type, BigDecimal amount,
                                      String currency, String description, Instant createdAt,
                                      String idempotencyKey, TransactionStatus status,
                                      String providerTransactionId, BigDecimal balanceAfter,
                                      String failureCode, String failureMessage) {
        return new Transaction(id, accountId, type, amount, currency, description, createdAt, idempotencyKey,
                status, providerTransactionId, balanceAfter, failureCode, failureMessage);
    }

    /**
     * Whether this transaction was created from the same request data. Tells a genuine retry apart
     * from a client reusing an idempotency key for a different request. Amounts are compared by
     * value, so {@code 100} and {@code 100.00} match.
     */
    public boolean matches(TransactionRequest request) {
        return accountId.equals(request.accountId())
                && type == request.type()
                && amount.compareTo(request.amount()) == 0
                && currency.equals(request.currency())
                && Objects.equals(description, request.description());
    }

    /** The provider approved the transaction and reported the resulting balance. */
    public void markExecuted(String providerTransactionId, BigDecimal balanceAfter) {
        transitionTo(TransactionStatus.EXECUTED);
        this.providerTransactionId = requireNotBlank(providerTransactionId, "providerTransactionId");
        this.balanceAfter = Objects.requireNonNull(balanceAfter, "balanceAfter must not be null");
    }

    /** The provider refused the transaction with a business reason (e.g. INSUFFICIENT_FUNDS). */
    public void markRejected(String code, String message) {
        transitionTo(TransactionStatus.REJECTED);
        this.failureCode = code;
        this.failureMessage = message;
    }

    /** The provider could not give a definitive answer (timeout, 5xx, ...). */
    public void markFailed(String code, String message) {
        transitionTo(TransactionStatus.FAILED);
        this.failureCode = code;
        this.failureMessage = message;
    }

    private void transitionTo(TransactionStatus target) {
        if (status.isFinal()) {
            throw new InvalidTransactionStateException(id, status, target);
        }
        this.status = target;
    }

    private static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** The client's idempotency key, or {@code null} if the request had none. */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Transaction other && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Transaction{id=%s, accountId=%s, type=%s, amount=%s %s, status=%s}"
                .formatted(id, accountId, type, amount, currency, status);
    }
}
