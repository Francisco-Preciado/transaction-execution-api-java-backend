package com.transactionexecution.api.domain.exception;

/**
 * Base type for every exception raised by the domain. Carries a stable, machine-readable
 * {@code code} so the API layer can map it to an error response without parsing messages.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected DomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
