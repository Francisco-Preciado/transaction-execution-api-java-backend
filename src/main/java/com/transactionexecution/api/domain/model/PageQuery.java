package com.transactionexecution.api.domain.model;

import com.transactionexecution.api.domain.exception.InvalidPageQueryException;

/**
 * A page request. The size is capped so a single query cannot load an unbounded number of rows.
 *
 * @param page zero-based page index
 * @param size number of items per page, between 1 and {@link #MAX_SIZE}
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        if (page < 0) {
            throw new InvalidPageQueryException("page must be >= 0, got " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidPageQueryException("size must be between 1 and %d, got %d".formatted(MAX_SIZE, size));
        }
    }

    public static PageQuery first() {
        return new PageQuery(0, DEFAULT_SIZE);
    }
}
