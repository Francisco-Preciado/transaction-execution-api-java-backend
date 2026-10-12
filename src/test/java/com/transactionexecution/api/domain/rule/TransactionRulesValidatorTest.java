package com.transactionexecution.api.domain.rule;

import com.transactionexecution.api.domain.exception.BusinessRuleViolationException;
import com.transactionexecution.api.domain.model.TransactionRequest;
import com.transactionexecution.api.domain.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRulesValidatorTest {

    private final TransactionRulesValidator validator = TransactionRulesValidator.withDefaultRules();

    @Test
    void acceptsValidCredit() {
        assertThatCode(() -> validator.validate(request(TransactionType.CREDIT, "1500.00", "MXN")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsDebitAtLimit() {
        assertThatCode(() -> validator.validate(request(TransactionType.DEBIT, "10000.00", "MXN")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsLowAmount() {
        assertViolation(request(TransactionType.CREDIT, "1.00", "MXN"), MinimumAmountRule.AMOUNT_TOO_LOW);
    }

    @Test
    void rejectsDebitOverLimit() {
        assertViolation(request(TransactionType.DEBIT, "10000.01", "MXN"), DebitLimitRule.DEBIT_LIMIT_EXCEEDED);
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertViolation(request(TransactionType.CREDIT, "100.00", "USD"), SupportedCurrencyRule.UNSUPPORTED_CURRENCY);
    }

    @Test
    void stopsAtFirstViolation() {
        List<String> evaluated = new ArrayList<>();
        TransactionRule failing = request -> {
            evaluated.add("first");
            throw new BusinessRuleViolationException("FIRST", "first rule failed");
        };
        TransactionRule neverReached = request -> evaluated.add("second");

        TransactionRulesValidator custom = new TransactionRulesValidator(List.of(failing, neverReached));

        assertThatThrownBy(() -> custom.validate(request(TransactionType.CREDIT, "100.00", "MXN")))
                .extracting("code").isEqualTo("FIRST");
        assertThat(evaluated).containsExactly("first");
    }

    private void assertViolation(TransactionRequest request, String expectedCode) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo(expectedCode);
    }

    private static TransactionRequest request(TransactionType type, String amount, String currency) {
        return new TransactionRequest("acc-123456", type, new BigDecimal(amount), currency, "test");
    }
}
