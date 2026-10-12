package com.transactionexecution.api.domain.rule;

import com.transactionexecution.api.domain.exception.BusinessRuleViolationException;
import com.transactionexecution.api.domain.model.TransactionRequest;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Every transaction must be strictly greater than the minimum amount (the minimum itself is rejected).
 */
public class MinimumAmountRule implements TransactionRule {

    public static final String AMOUNT_TOO_LOW = "AMOUNT_TOO_LOW";
    public static final BigDecimal DEFAULT_MINIMUM = new BigDecimal("1.00");

    private final BigDecimal minimum;

    public MinimumAmountRule(BigDecimal minimum) {
        this.minimum = Objects.requireNonNull(minimum, "minimum must not be null");
    }

    public MinimumAmountRule() {
        this(DEFAULT_MINIMUM);
    }

    @Override
    public void validate(TransactionRequest request) {
        if (request.amount().compareTo(minimum) <= 0) {
            throw new BusinessRuleViolationException(AMOUNT_TOO_LOW,
                    "Amount must be greater than %s, got %s".formatted(minimum.toPlainString(),
                            request.amount().toPlainString()));
        }
    }
}
