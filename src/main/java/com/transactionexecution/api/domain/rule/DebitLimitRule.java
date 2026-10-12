package com.transactionexecution.api.domain.rule;

import com.transactionexecution.api.domain.exception.BusinessRuleViolationException;
import com.transactionexecution.api.domain.model.TransactionRequest;
import com.transactionexecution.api.domain.model.TransactionType;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A single DEBIT cannot exceed the maximum amount (the maximum itself is allowed). CREDIT has no limit.
 */
public class DebitLimitRule implements TransactionRule {

    public static final String DEBIT_LIMIT_EXCEEDED = "DEBIT_LIMIT_EXCEEDED";
    public static final BigDecimal DEFAULT_MAXIMUM = new BigDecimal("10000.00");

    private final BigDecimal maximum;

    public DebitLimitRule(BigDecimal maximum) {
        this.maximum = Objects.requireNonNull(maximum, "maximum must not be null");
    }

    public DebitLimitRule() {
        this(DEFAULT_MAXIMUM);
    }

    @Override
    public void validate(TransactionRequest request) {
        if (request.type() == TransactionType.DEBIT && request.amount().compareTo(maximum) > 0) {
            throw new BusinessRuleViolationException(DEBIT_LIMIT_EXCEEDED,
                    "DEBIT amount cannot exceed %s per transaction, got %s".formatted(maximum.toPlainString(),
                            request.amount().toPlainString()));
        }
    }
}
