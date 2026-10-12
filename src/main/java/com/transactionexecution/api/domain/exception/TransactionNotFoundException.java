package com.transactionexecution.api.domain.exception;

import java.util.UUID;

public class TransactionNotFoundException extends DomainException {

    public static final String TRANSACTION_NOT_FOUND = "TRANSACTION_NOT_FOUND";

    public TransactionNotFoundException(UUID id) {
        super(TRANSACTION_NOT_FOUND, "Transaction %s not found".formatted(id));
    }
}
