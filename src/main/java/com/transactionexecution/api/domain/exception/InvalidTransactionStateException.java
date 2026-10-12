package com.transactionexecution.api.domain.exception;

import com.transactionexecution.api.domain.model.TransactionStatus;

import java.util.UUID;

/**
 * An attempt to move a transaction out of a final state (e.g. executing it twice).
 */
public class InvalidTransactionStateException extends DomainException {

    public static final String INVALID_TRANSACTION_STATE = "INVALID_TRANSACTION_STATE";

    public InvalidTransactionStateException(UUID transactionId, TransactionStatus current, TransactionStatus target) {
        super(INVALID_TRANSACTION_STATE,
                "Transaction %s cannot move from %s to %s".formatted(transactionId, current, target));
    }
}
