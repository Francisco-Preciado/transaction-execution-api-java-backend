package com.transactionexecution.api.application;

import com.transactionexecution.api.application.ExecuteTransactionUseCase.Execution;
import com.transactionexecution.api.domain.exception.BusinessRuleViolationException;
import com.transactionexecution.api.domain.exception.DuplicateIdempotencyKeyException;
import com.transactionexecution.api.domain.exception.IdempotencyKeyReusedException;
import com.transactionexecution.api.domain.exception.ProviderException;
import com.transactionexecution.api.domain.exception.RequestInProgressException;
import com.transactionexecution.api.domain.model.ProviderResult;
import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.model.TransactionRequest;
import com.transactionexecution.api.domain.model.TransactionStatus;
import com.transactionexecution.api.domain.model.TransactionType;
import com.transactionexecution.api.domain.port.TransactionProvider;
import com.transactionexecution.api.domain.port.TransactionWriteRepository;
import com.transactionexecution.api.domain.rule.TransactionRulesValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecuteTransactionUseCaseTest {

    private static final Instant NOW = Instant.parse("2025-03-15T10:30:00Z");
    private static final String KEY = "6f1c2a9e-4b7d-4e0a-9c3f-2d8b5e7a1f40";
    private static final ProviderResult APPROVED = new ProviderResult.Approved("txn-789", new BigDecimal("5500.00"), NOW);

    private final TransactionWriteRepository repository = mock(TransactionWriteRepository.class);
    private final TransactionProvider provider = mock(TransactionProvider.class);

    /** Status of the transaction at the moment of each save (the object itself is mutable). */
    private final List<TransactionStatus> savedStatuses = new ArrayList<>();

    private ExecuteTransactionUseCase useCase;

    @BeforeEach
    void setUp() {
        when(repository.save(any())).thenAnswer(invocation -> {
            Transaction transaction = invocation.getArgument(0);
            savedStatuses.add(transaction.getStatus());
            return transaction;
        });
        useCase = new ExecuteTransactionUseCase(TransactionRulesValidator.withDefaultRules(), repository, provider,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void approvedTransactionIsExecutedWithProviderBalance() {
        when(provider.execute(any())).thenReturn(
                new ProviderResult.Approved("txn-789", new BigDecimal("5500.00"), NOW));

        Transaction result = execute(request(TransactionType.CREDIT, "1500.00", "MXN"));

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(result.getProviderTransactionId()).isEqualTo("txn-789");
        assertThat(result.getBalanceAfter()).isEqualByComparingTo("5500.00");
        assertThat(result.getAccountId()).isEqualTo("acc-123456");
        assertThat(result.getAmount()).isEqualByComparingTo("1500.00");
        assertThat(result.getCreatedAt()).isEqualTo(NOW);
        assertThat(savedStatuses).containsExactly(TransactionStatus.PENDING, TransactionStatus.EXECUTED);
    }

    @Test
    void rejectedTransactionKeepsProviderReason() {
        when(provider.execute(any())).thenReturn(new ProviderResult.Rejected("INSUFFICIENT_FUNDS",
                "The account does not have enough balance to complete the transaction"));

        Transaction result = execute(request(TransactionType.DEBIT, "500.00", "MXN"));

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(result.getFailureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(result.getFailureMessage()).contains("enough balance");
        assertThat(result.getBalanceAfter()).isNull();
        assertThat(savedStatuses).containsExactly(TransactionStatus.PENDING, TransactionStatus.REJECTED);
    }

    @Test
    void providerWithoutAnswerMarksTransactionFailed() {
        when(provider.execute(any())).thenThrow(new ProviderException("Provider answered HTTP 503"));

        Transaction result = execute(request(TransactionType.DEBIT, "500.00", "MXN"));

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(ProviderException.PROVIDER_UNAVAILABLE);
        assertThat(result.getFailureMessage()).isEqualTo("Provider answered HTTP 503");
        assertThat(savedStatuses).containsExactly(TransactionStatus.PENDING, TransactionStatus.FAILED);
    }

    @Test
    void persistsPendingBeforeCallingProvider() {
        when(provider.execute(any())).thenReturn(
                new ProviderResult.Approved("txn-789", new BigDecimal("5500.00"), NOW));

        execute(request(TransactionType.CREDIT, "1500.00", "MXN"));

        InOrder order = inOrder(repository, provider);
        order.verify(repository).save(any());
        order.verify(provider).execute(any());
        order.verify(repository).save(any());
    }

    @ParameterizedTest
    @CsvSource({
            "CREDIT, 1.00,     MXN, AMOUNT_TOO_LOW",
            "DEBIT,  10000.01, MXN, DEBIT_LIMIT_EXCEEDED",
            "CREDIT, 100.00,   USD, UNSUPPORTED_CURRENCY"
    })
    void ruleViolationNeverReachesProviderOrDatabase(TransactionType type, String amount, String currency,
                                                     String expectedCode) {
        assertThatThrownBy(() -> execute(request(type, amount, currency)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo(expectedCode);

        verifyNoInteractions(provider);
        verify(repository, times(0)).save(any());
    }

    @Test
    void unexpectedErrorLeavesTransactionPendingForReconciliation() {
        when(provider.execute(any())).thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(() -> execute(request(TransactionType.CREDIT, "1500.00", "MXN")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(savedStatuses).containsExactly(TransactionStatus.PENDING);
    }

    // ---- Idempotency-Key ----

    @Test
    void withoutKeyNeverLooksUpPreviousRequests() {
        when(provider.execute(any())).thenReturn(APPROVED);

        execute(request(TransactionType.CREDIT, "1500.00", "MXN"));

        verify(repository, never()).findByIdempotencyKey(any());
    }

    @Test
    void newKeyExecutesAndStoresKey() {
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(provider.execute(any())).thenReturn(APPROVED);

        Execution execution = useCase.execute(request(TransactionType.CREDIT, "1500.00", "MXN"), KEY);

        assertThat(execution.replayed()).isFalse();
        assertThat(execution.transaction().getIdempotencyKey()).isEqualTo(KEY);
        assertThat(execution.transaction().getStatus()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(savedStatuses).containsExactly(TransactionStatus.PENDING, TransactionStatus.EXECUTED);
    }

    @Test
    void repeatedKeyReturnsStoredTransactionWithoutExecutingAgain() {
        Transaction previous = storedTransaction(TransactionType.DEBIT, "500.00");
        previous.markExecuted("txn-789", new BigDecimal("4500.00"));
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(previous));

        Execution execution = useCase.execute(request(TransactionType.DEBIT, "500.00", "MXN"), KEY);

        assertThat(execution.replayed()).isTrue();
        assertThat(execution.transaction()).isSameAs(previous);
        verifyNoInteractions(provider);
        verify(repository, never()).save(any());
    }

    @Test
    void repeatedKeyReplaysEvenIfRulesWouldNowReject() {
        Transaction previous = storedTransaction(TransactionType.DEBIT, "1.00");
        previous.markRejected("INSUFFICIENT_FUNDS", "No balance");
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(previous));

        Execution execution = useCase.execute(request(TransactionType.DEBIT, "1.00", "MXN"), KEY);

        assertThat(execution.replayed()).isTrue();
        assertThat(execution.transaction().getStatus()).isEqualTo(TransactionStatus.REJECTED);
    }

    @Test
    void keyReusedForDifferentRequestIsRefused() {
        when(repository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(storedTransaction(TransactionType.DEBIT, "500.00")));

        assertThatThrownBy(() -> useCase.execute(request(TransactionType.DEBIT, "600.00", "MXN"), KEY))
                .isInstanceOf(IdempotencyKeyReusedException.class)
                .extracting("code").isEqualTo(IdempotencyKeyReusedException.IDEMPOTENCY_KEY_REUSED);

        verifyNoInteractions(provider);
        verify(repository, never()).save(any());
    }

    @Test
    void repeatedKeyWhileFirstRequestIsPendingIsRefused() {
        when(repository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(storedTransaction(TransactionType.DEBIT, "500.00")));

        assertThatThrownBy(() -> useCase.execute(request(TransactionType.DEBIT, "500.00", "MXN"), KEY))
                .isInstanceOf(RequestInProgressException.class)
                .extracting("code").isEqualTo(RequestInProgressException.REQUEST_IN_PROGRESS);

        verifyNoInteractions(provider);
    }

    @Test
    void concurrentRequestThatLosesTheInsertReplaysTheWinner() {
        Transaction winner = storedTransaction(TransactionType.DEBIT, "500.00");
        winner.markExecuted("txn-789", new BigDecimal("4500.00"));
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty()).thenReturn(Optional.of(winner));
        doThrow(new DuplicateIdempotencyKeyException(KEY, null)).when(repository).save(any());

        Execution execution = useCase.execute(request(TransactionType.DEBIT, "500.00", "MXN"), KEY);

        assertThat(execution.replayed()).isTrue();
        assertThat(execution.transaction()).isSameAs(winner);
        verifyNoInteractions(provider);
    }

    private Transaction execute(TransactionRequest request) {
        return useCase.execute(request, null).transaction();
    }

    /** A transaction stored earlier with {@link #KEY}, still PENDING. */
    private static Transaction storedTransaction(TransactionType type, String amount) {
        return Transaction.create(request(type, amount, "MXN"), KEY, NOW.minusSeconds(60));
    }

    private static TransactionRequest request(TransactionType type, String amount, String currency) {
        return new TransactionRequest("acc-123456", type, new BigDecimal(amount), currency, "Transferencia recibida");
    }
}
