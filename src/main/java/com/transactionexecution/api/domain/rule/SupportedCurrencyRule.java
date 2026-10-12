package com.transactionexecution.api.domain.rule;

import com.transactionexecution.api.domain.exception.BusinessRuleViolationException;
import com.transactionexecution.api.domain.model.TransactionRequest;

import java.util.Objects;
import java.util.Set;

/**
 * Only transactions in a supported currency are accepted. Codes are ISO 4217 and compared exactly
 * ("mxn" is rejected), so what we store and send to the provider is always canonical.
 */
public class SupportedCurrencyRule implements TransactionRule {

    public static final String UNSUPPORTED_CURRENCY = "UNSUPPORTED_CURRENCY";
    public static final Set<String> DEFAULT_CURRENCIES = Set.of("MXN");

    private final Set<String> supportedCurrencies;

    public SupportedCurrencyRule(Set<String> supportedCurrencies) {
        this.supportedCurrencies = Set.copyOf(Objects.requireNonNull(supportedCurrencies,
                "supportedCurrencies must not be null"));
    }

    public SupportedCurrencyRule() {
        this(DEFAULT_CURRENCIES);
    }

    @Override
    public void validate(TransactionRequest request) {
        if (!supportedCurrencies.contains(request.currency())) {
            throw new BusinessRuleViolationException(UNSUPPORTED_CURRENCY,
                    "Currency %s is not supported. Supported: %s".formatted(request.currency(),
                            String.join(", ", supportedCurrencies.stream().sorted().toList())));
        }
    }
}
