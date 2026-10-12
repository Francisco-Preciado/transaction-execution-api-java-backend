package com.transactionexecution.api.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Definitive answer from the external provider. Being sealed, callers can switch over it
 * exhaustively. Situations without a definitive answer (timeouts, 5xx) are not results; they
 * surface as {@code ProviderException}.
 */
public sealed interface ProviderResult {

    record Approved(String providerTransactionId, BigDecimal balance, Instant executedAt) implements ProviderResult {
    }

    record Rejected(String code, String message) implements ProviderResult {
    }
}
