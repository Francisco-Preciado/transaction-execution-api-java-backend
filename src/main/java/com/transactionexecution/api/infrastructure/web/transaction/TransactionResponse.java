package com.transactionexecution.api.infrastructure.web.transaction;

import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.model.TransactionStatus;
import com.transactionexecution.api.domain.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A stored transaction. {@code providerTransactionId} and {@code balanceAfter} are set when
 * EXECUTED; {@code failureCode} and {@code failureMessage} when REJECTED or FAILED.
 */
public record TransactionResponse(
        UUID id,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        String description,
        TransactionStatus status,
        String providerTransactionId,
        BigDecimal balanceAfter,
        String failureCode,
        String failureMessage,
        Instant createdAt) {

    static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getAccountId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDescription(),
                transaction.getStatus(),
                transaction.getProviderTransactionId(),
                transaction.getBalanceAfter(),
                transaction.getFailureCode(),
                transaction.getFailureMessage(),
                transaction.getCreatedAt());
    }
}
