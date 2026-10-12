package com.transactionexecution.api.domain.port;

import com.transactionexecution.api.domain.exception.ProviderException;
import com.transactionexecution.api.domain.model.ProviderResult;
import com.transactionexecution.api.domain.model.Transaction;

/**
 * Outbound port to the external provider that owns balances and locks.
 */
public interface TransactionProvider {

    /**
     * Executes the transaction at the provider.
     *
     * @return {@link ProviderResult.Approved} or {@link ProviderResult.Rejected}
     * @throws ProviderException when the provider gives no definitive answer (timeout, 5xx, ...)
     */
    ProviderResult execute(Transaction transaction);
}
