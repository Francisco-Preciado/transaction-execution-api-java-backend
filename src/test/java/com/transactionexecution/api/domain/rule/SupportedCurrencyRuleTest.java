package com.transactionexecution.api.domain.rule;

import com.transactionexecution.api.domain.exception.BusinessRuleViolationException;
import com.transactionexecution.api.domain.model.TransactionRequest;
import com.transactionexecution.api.domain.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportedCurrencyRuleTest {

    private final SupportedCurrencyRule rule = new SupportedCurrencyRule();

    @Test
    void acceptsMxn() {
        assertThatCode(() -> rule.validate(request("MXN"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "EUR", "mxn", "MX", "XXX"})
    void rejectsAnyOtherCurrency(String currency) {
        assertThatThrownBy(() -> rule.validate(request(currency)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining(currency)
                .hasMessageContaining("Supported: MXN")
                .extracting("code").isEqualTo(SupportedCurrencyRule.UNSUPPORTED_CURRENCY);
    }

    private static TransactionRequest request(String currency) {
        return new TransactionRequest("acc-1", TransactionType.CREDIT, new BigDecimal("100.00"), currency, null);
    }
}
