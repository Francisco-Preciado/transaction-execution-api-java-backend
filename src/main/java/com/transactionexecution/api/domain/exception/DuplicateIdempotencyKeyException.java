package com.transactionexecution.api.domain.exception;

/**
 * Raised by the repository when a new transaction cannot be stored because another one already
 * holds its idempotency key: two requests with the same key arrived at the same time.
 */
public class DuplicateIdempotencyKeyException extends DomainException {

    public static final String DUPLICATE_IDEMPOTENCY_KEY = "DUPLICATE_IDEMPOTENCY_KEY";

    public DuplicateIdempotencyKeyException(String idempotencyKey, Throwable cause) {
        super(DUPLICATE_IDEMPOTENCY_KEY,
                "A transaction with idempotency key '%s' already exists".formatted(idempotencyKey), cause);
    }
}
