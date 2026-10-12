package com.transactionexecution.api.domain.port;

import com.transactionexecution.api.domain.exception.DuplicateIdempotencyKeyException;
import com.transactionexecution.api.domain.model.Transaction;

import java.util.Optional;

/**
 * Outbound port for the command side: storing transactions and the lookups the execution flow
 * needs to stay consistent. Always backed by the primary database, so reads here are never stale.
 */
public interface TransactionWriteRepository {

    /**
     * Inserts or updates the transaction and returns the stored state.
     *
     * @throws DuplicateIdempotencyKeyException when inserting a transaction whose idempotency key
     *                                          is already taken by another one
     */
    Transaction save(Transaction transaction);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
