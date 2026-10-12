package com.transactionexecution.api.domain.model;

/**
 * Optional search criteria; a {@code null} field means "do not filter by it".
 */
public record TransactionFilter(String accountId, TransactionStatus status, TransactionType type) {

    public static TransactionFilter none() {
        return new TransactionFilter(null, null, null);
    }
}
