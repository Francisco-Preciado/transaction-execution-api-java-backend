package com.transactionexecution.api.domain.rule;

import com.transactionexecution.api.domain.exception.BusinessRuleViolationException;
import com.transactionexecution.api.domain.model.TransactionRequest;

import java.util.List;

/**
 * Runs every business rule against a request, in order, and stops at the first violation.
 * Must be called before the transaction is persisted or sent to the provider.
 */
public class TransactionRulesValidator {

    private final List<TransactionRule> rules;

    public TransactionRulesValidator(List<TransactionRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** The rules required by the challenge, with their default limits. */
    public static TransactionRulesValidator withDefaultRules() {
        return new TransactionRulesValidator(List.of(
                new SupportedCurrencyRule(),
                new MinimumAmountRule(),
                new DebitLimitRule()));
    }

    /**
     * @throws BusinessRuleViolationException with the first rule the request breaks
     */
    public void validate(TransactionRequest request) {
        rules.forEach(rule -> rule.validate(request));
    }
}
