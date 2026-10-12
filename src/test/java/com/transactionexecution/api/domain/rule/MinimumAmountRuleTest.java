package com.transactionexecution.api.domain.rule;

import com.transactionexecution.api.domain.exception.BusinessRuleViolationException;
import com.transactionexecution.api.domain.model.TransactionRequest;
import com.transactionexecution.api.domain.model.TransactionType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinimumAmountRuleTest {

    private final MinimumAmountRule rule = new MinimumAmountRule();

    @ParameterizedTest
    @ValueSource(strings = {"1.01", "1.010", "1500.00", "99999999.99"})
    void acceptsAmountsAboveMinimum(String amount) {
        assertThatCode(() -> rule.validate(request(TransactionType.CREDIT, amount))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.00", "1", "1.000", "0.99", "0", "-5.00"})
    void rejectsAmountsAtOrBelowMinimum(String amount) {
        assertThatThrownBy(() -> rule.validate(request(TransactionType.CREDIT, amount)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("greater than 1.00")
                .extracting("code").isEqualTo(MinimumAmountRule.AMOUNT_TOO_LOW);
    }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void appliesToEveryType(TransactionType type) {
        assertThatThrownBy(() -> rule.validate(request(type, "1.00")))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    private static TransactionRequest request(TransactionType type, String amount) {
        return new TransactionRequest("acc-1", type, new BigDecimal(amount), "MXN", null);
    }
}
