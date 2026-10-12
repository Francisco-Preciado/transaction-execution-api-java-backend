package com.transactionexecution.api.domain.rule;

import com.transactionexecution.api.domain.exception.BusinessRuleViolationException;
import com.transactionexecution.api.domain.model.TransactionRequest;
import com.transactionexecution.api.domain.model.TransactionType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebitLimitRuleTest {

    private final DebitLimitRule rule = new DebitLimitRule();

    @ParameterizedTest
    @ValueSource(strings = {"1.01", "9999.99", "10000.00", "10000"})
    void acceptsDebitsUpToLimit(String amount) {
        assertThatCode(() -> rule.validate(request(TransactionType.DEBIT, amount))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000.01", "10000.010", "50000.00"})
    void rejectsDebitsAboveLimit(String amount) {
        assertThatThrownBy(() -> rule.validate(request(TransactionType.DEBIT, amount)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot exceed 10000.00")
                .extracting("code").isEqualTo(DebitLimitRule.DEBIT_LIMIT_EXCEEDED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000.01", "1000000.00"})
    void creditsHaveNoLimit(String amount) {
        assertThatCode(() -> rule.validate(request(TransactionType.CREDIT, amount))).doesNotThrowAnyException();
    }

    private static TransactionRequest request(TransactionType type, String amount) {
        return new TransactionRequest("acc-1", type, new BigDecimal(amount), "MXN", null);
    }
}
