package com.transactionexecution.api.domain.exception;

/**
 * The client sent an idempotency key that was already used for a different request (other
 * account, type, amount, currency or description). Keys identify one request, never a new one.
 */
public class IdempotencyKeyReusedException extends DomainException {

    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super(IDEMPOTENCY_KEY_REUSED,
                "Idempotency key '%s' was already used for a different request".formatted(idempotencyKey));
    }
}
