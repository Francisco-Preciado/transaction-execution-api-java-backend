package com.transactionexecution.api.domain.model;

import com.transactionexecution.api.domain.exception.InvalidTransactionStateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionTest {

    private static final Instant NOW = Instant.parse("2025-03-15T10:30:00Z");

    @Test
    void createStartsPendingWithoutOutcome() {
        Transaction tx = newTransaction();

        assertThat(tx.getId()).isNotNull();
        assertThat(tx.getAccountId()).isEqualTo("acc-123456");
        assertThat(tx.getType()).isEqualTo(TransactionType.CREDIT);
        assertThat(tx.getAmount()).isEqualByComparingTo("1500.00");
        assertThat(tx.getCurrency()).isEqualTo("MXN");
        assertThat(tx.getDescription()).isEqualTo("Transferencia recibida");
        assertThat(tx.getCreatedAt()).isEqualTo(NOW);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.getProviderTransactionId()).isNull();
        assertThat(tx.getBalanceAfter()).isNull();
        assertThat(tx.getFailureCode()).isNull();
    }

    @Test
    void createGeneratesUniqueIds() {
        assertThat(newTransaction().getId()).isNotEqualTo(newTransaction().getId());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void createRejectsBlankAccountId(String accountId) {
        assertThatThrownBy(() -> Transaction.create(accountId, TransactionType.CREDIT,
                new BigDecimal("10.00"), "MXN", null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId");
    }

    @Test
    void createRejectsMissingAmount() {
        assertThatThrownBy(() -> Transaction.create("acc-1", TransactionType.DEBIT, null, "MXN", null, NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void markExecutedStoresProviderOutcome() {
        Transaction tx = newTransaction();

        tx.markExecuted("txn-789", new BigDecimal("5500.00"));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(tx.getProviderTransactionId()).isEqualTo("txn-789");
        assertThat(tx.getBalanceAfter()).isEqualByComparingTo("5500.00");
    }

    @Test
    void markRejectedStoresReason() {
        Transaction tx = newTransaction();

        tx.markRejected("INSUFFICIENT_FUNDS", "Not enough balance");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(tx.getFailureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(tx.getFailureMessage()).isEqualTo("Not enough balance");
        assertThat(tx.getBalanceAfter()).isNull();
    }

    @Test
    void markFailedStoresReason() {
        Transaction tx = newTransaction();

        tx.markFailed("PROVIDER_UNAVAILABLE", "Timeout");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.getFailureCode()).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    void finalStateCannotChange() {
        Transaction tx = newTransaction();
        tx.markExecuted("txn-789", new BigDecimal("5500.00"));

        assertThatThrownBy(() -> tx.markRejected("ANY", "any"))
                .isInstanceOf(InvalidTransactionStateException.class)
                .hasMessageContaining("EXECUTED")
                .hasMessageContaining("REJECTED");
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.EXECUTED);
    }

    @Test
    void restoreKeepsAllFields() {
        Transaction original = newTransaction();
        original.markExecuted("txn-789", new BigDecimal("5500.00"));

        Transaction restored = Transaction.restore(original.getId(), original.getAccountId(), original.getType(),
                original.getAmount(), original.getCurrency(), original.getDescription(), original.getCreatedAt(),
                original.getIdempotencyKey(), original.getStatus(), original.getProviderTransactionId(),
                original.getBalanceAfter(), original.getFailureCode(), original.getFailureMessage());

        assertThat(restored).usingRecursiveComparison().isEqualTo(original);
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void createFromRequestKeepsIdempotencyKey() {
        TransactionRequest request = new TransactionRequest("acc-123456", TransactionType.CREDIT,
                new BigDecimal("1500.00"), "MXN", "Transferencia recibida");

        Transaction tx = Transaction.create(request, "key-1", NOW);

        assertThat(tx.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.matches(request)).isTrue();
    }

    @Test
    void matchesComparesAmountByValue() {
        assertThat(newTransaction().matches(new TransactionRequest("acc-123456", TransactionType.CREDIT,
                new BigDecimal("1500"), "MXN", "Transferencia recibida"))).isTrue();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "acc-other,  CREDIT, 1500.00, MXN, Transferencia recibida",
            "acc-123456, DEBIT,  1500.00, MXN, Transferencia recibida",
            "acc-123456, CREDIT, 1500.01, MXN, Transferencia recibida",
            "acc-123456, CREDIT, 1500.00, USD, Transferencia recibida",
            "acc-123456, CREDIT, 1500.00, MXN, Otra descripcion",
            "acc-123456, CREDIT, 1500.00, MXN, null"
    })
    void doesNotMatchADifferentRequest(String accountId, TransactionType type, BigDecimal amount,
                                       String currency, String description) {
        assertThat(newTransaction().matches(new TransactionRequest(accountId, type, amount, currency, description)))
                .isFalse();
    }

    private static Transaction newTransaction() {
        return Transaction.create("acc-123456", TransactionType.CREDIT, new BigDecimal("1500.00"),
                "MXN", "Transferencia recibida", NOW);
    }
}
