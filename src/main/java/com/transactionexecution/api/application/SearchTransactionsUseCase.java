package com.transactionexecution.api.application;

import com.transactionexecution.api.domain.exception.TransactionNotFoundException;
import com.transactionexecution.api.domain.model.PageQuery;
import com.transactionexecution.api.domain.model.PageResult;
import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.model.TransactionFilter;
import com.transactionexecution.api.domain.port.TransactionReadRepository;

import java.util.UUID;

/**
 * Read side: look up stored transactions by id or by filter, newest first.
 */
public class SearchTransactionsUseCase {

    private final TransactionReadRepository transactionRepository;

    public SearchTransactionsUseCase(TransactionReadRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public PageResult<Transaction> search(TransactionFilter filter, PageQuery pageQuery) {
        return transactionRepository.search(filter, pageQuery);
    }

    /**
     * @throws TransactionNotFoundException when no transaction has this id
     */
    public Transaction getById(UUID id) {
        return transactionRepository.findById(id).orElseThrow(() -> new TransactionNotFoundException(id));
    }
}
