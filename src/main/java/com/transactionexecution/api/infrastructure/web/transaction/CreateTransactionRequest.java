package com.transactionexecution.api.infrastructure.web.transaction;

import com.transactionexecution.api.domain.model.TransactionRequest;
import com.transactionexecution.api.domain.model.TransactionType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Body of {@code POST /transactions}. Only checks the shape of the input (400 when invalid);
 * business rules such as the minimum amount or supported currency are the domain's job (422).
 */
public record CreateTransactionRequest(
        @NotBlank @Size(max = 64) String accountId,
        @NotNull TransactionType type,
        @NotNull @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 3) String currency,
        @Size(max = 255) String description) {

    TransactionRequest toDomain() {
        return new TransactionRequest(accountId, type, amount, currency, description);
    }
}
