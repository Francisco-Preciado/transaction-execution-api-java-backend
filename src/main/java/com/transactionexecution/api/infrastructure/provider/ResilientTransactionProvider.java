package com.transactionexecution.api.infrastructure.provider;

import com.transactionexecution.api.domain.exception.ProviderException;
import com.transactionexecution.api.domain.model.ProviderResult;
import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.port.TransactionProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

import java.util.function.Supplier;

/**
 * Decorator that adds a circuit breaker and retries around another {@link TransactionProvider}.
 *
 * <p>Order: {@code Retry(CircuitBreaker(call))}. Every attempt is recorded by the circuit breaker,
 * and once it opens, calls fail fast without reaching the provider.
 *
 * <p>Only {@link ProviderException} (no definitive answer) is retried and counted as a failure;
 * a {@link ProviderResult.Rejected} is a normal answer and returns immediately. Retries are safe
 * because every attempt sends the same idempotency key (the transaction id).
 */
public class ResilientTransactionProvider implements TransactionProvider {

    private final TransactionProvider delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ResilientTransactionProvider(TransactionProvider delegate, CircuitBreaker circuitBreaker, Retry retry) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    @Override
    public ProviderResult execute(Transaction transaction) {
        Supplier<ProviderResult> call = () -> delegate.execute(transaction);
        Supplier<ProviderResult> resilientCall =
                Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call));
        try {
            return resilientCall.get();
        } catch (CallNotPermittedException e) {
            throw new ProviderException("Provider temporarily unavailable (circuit breaker open)", e);
        }
    }
}
