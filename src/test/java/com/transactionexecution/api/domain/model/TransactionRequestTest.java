package com.transactionexecution.api.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRequestTest {

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsBlankAccountId(String accountId) {
        assertThatThrownBy(() -> new TransactionRequest(accountId, TransactionType.CREDIT, AMOUNT, "MXN", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsBlankCurrency(String currency) {
        assertThatThrownBy(() -> new TransactionRequest("acc-1", TransactionType.CREDIT, AMOUNT, currency, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void rejectsMissingTypeOrAmount() {
        assertThatThrownBy(() -> new TransactionRequest("acc-1", null, AMOUNT, "MXN", null))
                .hasMessageContaining("type");
        assertThatThrownBy(() -> new TransactionRequest("acc-1", TransactionType.CREDIT, null, "MXN", null))
                .hasMessageContaining("amount");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.001", "10.123", "0.0001"})
    void rejectsMoreThanTwoDecimals(String amount) {
        assertThatThrownBy(() -> new TransactionRequest("acc-1", TransactionType.CREDIT, new BigDecimal(amount),
                "MXN", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 2 decimal places");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "10.5", "10.50", "10.500"})
    void acceptsUpToTwoSignificantDecimals(String amount) {
        assertThat(new TransactionRequest("acc-1", TransactionType.CREDIT, new BigDecimal(amount), "MXN", null)
                .amount()).isEqualByComparingTo(amount);
    }

    @Test
    void descriptionIsOptional() {
        assertThat(new TransactionRequest("acc-1", TransactionType.CREDIT, AMOUNT, "MXN", null).description())
                .isNull();
    }

    @Test
    void createsPendingTransactionFromRequest() {
        Instant now = Instant.parse("2025-03-15T10:30:00Z");
        TransactionRequest request = new TransactionRequest("acc-1", TransactionType.DEBIT, AMOUNT, "MXN", "rent");

        Transaction tx = Transaction.create(request, null, now);

        assertThat(tx.getAccountId()).isEqualTo("acc-1");
        assertThat(tx.getType()).isEqualTo(TransactionType.DEBIT);
        assertThat(tx.getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(tx.getCurrency()).isEqualTo("MXN");
        assertThat(tx.getDescription()).isEqualTo("rent");
        assertThat(tx.getCreatedAt()).isEqualTo(now);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.getIdempotencyKey()).isNull();
    }
}
