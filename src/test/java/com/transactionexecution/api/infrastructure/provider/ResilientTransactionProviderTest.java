package com.transactionexecution.api.infrastructure.provider;

import com.transactionexecution.api.domain.exception.ProviderException;
import com.transactionexecution.api.domain.model.ProviderResult;
import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.model.TransactionType;
import com.transactionexecution.api.domain.port.TransactionProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilientTransactionProviderTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final ProviderResult APPROVED =
            new ProviderResult.Approved("txn-789", new BigDecimal("5500.00"), Instant.now());

    private final TransactionProvider delegate = mock(TransactionProvider.class);
    private final Transaction transaction = Transaction.create("acc-1", TransactionType.DEBIT,
            new BigDecimal("100.00"), "MXN", null, Instant.now());

    private CircuitBreaker circuitBreaker;
    private ResilientTransactionProvider provider;

    @BeforeEach
    void setUp() {
        circuitBreaker = CircuitBreaker.of("provider", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .recordExceptions(ProviderException.class)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
        Retry retry = Retry.of("provider", RetryConfig.custom()
                .maxAttempts(MAX_ATTEMPTS)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(ProviderException.class)
                .build());
        provider = new ResilientTransactionProvider(delegate, circuitBreaker, retry);
    }

    @Test
    void returnsResultWithoutRetryingOnSuccess() {
        when(delegate.execute(transaction)).thenReturn(APPROVED);

        assertThat(provider.execute(transaction)).isEqualTo(APPROVED);
        verify(delegate, times(1)).execute(transaction);
    }

    @Test
    void neverRetriesARejection() {
        ProviderResult rejected = new ProviderResult.Rejected("INSUFFICIENT_FUNDS", "no balance");
        when(delegate.execute(transaction)).thenReturn(rejected);

        assertThat(provider.execute(transaction)).isEqualTo(rejected);
        verify(delegate, times(1)).execute(transaction);
    }

    @Test
    void retriesTransientFailureUntilSuccess() {
        when(delegate.execute(transaction))
                .thenThrow(new ProviderException("timeout"))
                .thenReturn(APPROVED);

        assertThat(provider.execute(transaction)).isEqualTo(APPROVED);
        verify(delegate, times(2)).execute(transaction);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        when(delegate.execute(transaction)).thenThrow(new ProviderException("HTTP 503"));

        assertThatThrownBy(() -> provider.execute(transaction))
                .isInstanceOf(ProviderException.class)
                .hasMessage("HTTP 503");
        verify(delegate, times(MAX_ATTEMPTS)).execute(transaction);
    }

    @Test
    void doesNotRetryUnexpectedErrors() {
        when(delegate.execute(transaction)).thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(() -> provider.execute(transaction)).isInstanceOf(IllegalStateException.class);
        verify(delegate, times(1)).execute(transaction);
    }

    @Test
    void failsFastWhenCircuitIsOpen() {
        circuitBreaker.transitionToOpenState();

        assertThatThrownBy(() -> provider.execute(transaction))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("circuit breaker open");
        verify(delegate, never()).execute(transaction);
    }

    @Test
    void opensCircuitAfterRepeatedFailures() {
        when(delegate.execute(transaction)).thenThrow(new ProviderException("HTTP 503"));

        // 4 calls x 3 attempts = 12 recorded failures, above the 10-call window
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> provider.execute(transaction)).isInstanceOf(ProviderException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
