package com.transactionexecution.api.domain.exception;

/** Pagination parameters out of range (negative page, size above the maximum, ...). */
public class InvalidPageQueryException extends DomainException {

    public static final String INVALID_PAGE_QUERY = "INVALID_PAGE_QUERY";

    public InvalidPageQueryException(String message) {
        super(INVALID_PAGE_QUERY, message);
    }
}
