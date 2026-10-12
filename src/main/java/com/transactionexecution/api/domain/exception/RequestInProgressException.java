package com.transactionexecution.api.domain.exception;

/**
 * A request with the same idempotency key is still being executed (its transaction is PENDING).
 * The client should retry later with the same key to get the final result.
 */
public class RequestInProgressException extends DomainException {

    public static final String REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS";

    public RequestInProgressException(String idempotencyKey) {
        super(REQUEST_IN_PROGRESS,
                "A request with idempotency key '%s' is still in progress; retry later".formatted(idempotencyKey));
    }
}
