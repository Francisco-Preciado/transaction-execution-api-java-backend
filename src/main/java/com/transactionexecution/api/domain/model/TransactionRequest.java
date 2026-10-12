package com.transactionexecution.api.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A request to execute a transaction, before any business rule has been checked.
 * Structural checks (required fields) happen here; business rules live in {@code domain.rule}.
 */
public record TransactionRequest(String accountId, TransactionType type, BigDecimal amount,
                                 String currency, String description) {

    /** Money is stored as NUMERIC(19,2); more decimals would be silently rounded. */
    public static final int MAX_AMOUNT_DECIMALS = 2;

    public TransactionRequest {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId must not be blank");
        }
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.stripTrailingZeros().scale() > MAX_AMOUNT_DECIMALS) {
            throw new IllegalArgumentException(
                    "amount must have at most %d decimal places, got %s".formatted(MAX_AMOUNT_DECIMALS,
                            amount.toPlainString()));
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
    }
}
