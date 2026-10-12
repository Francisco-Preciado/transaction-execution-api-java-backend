package com.transactionexecution.api.domain.model;

/**
 * Lifecycle of a transaction. A transaction starts as {@link #PENDING} and moves exactly once
 * to one of the final states.
 */
public enum TransactionStatus {
    /** Persisted and about to be sent to the provider. */
    PENDING,
    /** The provider approved and applied the transaction. */
    EXECUTED,
    /** The provider refused the transaction (e.g. insufficient funds). */
    REJECTED,
    /** The provider could not be reached or answered with an unexpected error; outcome unknown. */
    FAILED;

    public boolean isFinal() {
        return this != PENDING;
    }
}
