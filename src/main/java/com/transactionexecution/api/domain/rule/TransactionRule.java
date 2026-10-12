package com.transactionexecution.api.domain.rule;

import com.transactionexecution.api.domain.exception.BusinessRuleViolationException;
import com.transactionexecution.api.domain.model.TransactionRequest;

/**
 * A single business rule checked before a transaction is sent to the provider.
 * New rules are added as new implementations, without touching the existing ones.
 */
public interface TransactionRule {

    /**
     * @throws BusinessRuleViolationException when the request breaks the rule
     */
    void validate(TransactionRequest request);
}
