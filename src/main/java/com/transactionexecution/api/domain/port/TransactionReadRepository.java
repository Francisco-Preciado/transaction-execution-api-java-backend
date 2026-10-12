package com.transactionexecution.api.domain.port;

import com.transactionexecution.api.domain.model.PageQuery;
import com.transactionexecution.api.domain.model.PageResult;
import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.model.TransactionFilter;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the query side: read-only access to stored transactions. Kept apart from
 * {@link TransactionWriteRepository} so it can later be served by a read replica or a dedicated
 * read model without touching the execution flow.
 */
public interface TransactionReadRepository {

    Optional<Transaction> findById(UUID id);

    /** Returns the transactions matching the filter, newest first. */
    PageResult<Transaction> search(TransactionFilter filter, PageQuery pageQuery);
}
