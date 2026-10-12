package com.transactionexecution.api.infrastructure.provider.dto;

import com.transactionexecution.api.domain.model.Transaction;

import java.math.BigDecimal;

/** Body of {@code POST /provider/v1/execute}. */
public record ProviderExecuteRequest(String accountId, String type, BigDecimal amount, String currency) {

    public static ProviderExecuteRequest from(Transaction transaction) {
        return new ProviderExecuteRequest(transaction.getAccountId(), transaction.getType().name(),
                transaction.getAmount(), transaction.getCurrency());
    }
}
