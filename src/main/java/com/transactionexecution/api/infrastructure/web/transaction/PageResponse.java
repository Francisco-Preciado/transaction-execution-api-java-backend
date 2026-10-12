package com.transactionexecution.api.infrastructure.web.transaction;

import com.transactionexecution.api.domain.model.PageResult;

import java.util.List;
import java.util.function.Function;

/**
 * A page of results. {@code page} is 1-based, as in the request.
 */
public record PageResponse<T>(List<T> items, int page, int limit, long totalItems, int totalPages) {

    static <D, T> PageResponse<T> from(PageResult<D> result, Function<D, T> mapper) {
        return new PageResponse<>(result.items().stream().map(mapper).toList(), result.page() + 1, result.size(),
                result.totalItems(), result.totalPages());
    }
}
