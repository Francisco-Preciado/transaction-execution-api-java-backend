package com.transactionexecution.api.domain.exception;

/**
 * A transaction request breaks one of the business rules (minimum amount, debit limit,
 * supported currency, ...). Raised before the provider is called.
 */
public class BusinessRuleViolationException extends DomainException {

    public BusinessRuleViolationException(String code, String message) {
        super(code, message);
    }
}
