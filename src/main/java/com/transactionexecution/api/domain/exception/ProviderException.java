package com.transactionexecution.api.domain.exception;

/**
 * The external provider could not give a definitive answer: timeout, connection error, 5xx or an
 * unreadable response. The outcome of the transaction is unknown, unlike a provider rejection,
 * which is a valid business answer (see {@code ProviderResult.Rejected}).
 */
public class ProviderException extends DomainException {

    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";

    public ProviderException(String message, Throwable cause) {
        super(PROVIDER_UNAVAILABLE, message, cause);
    }

    public ProviderException(String message) {
        super(PROVIDER_UNAVAILABLE, message);
    }
}
