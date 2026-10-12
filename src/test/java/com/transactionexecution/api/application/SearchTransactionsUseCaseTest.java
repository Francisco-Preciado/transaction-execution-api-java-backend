package com.transactionexecution.api.application;

import com.transactionexecution.api.domain.exception.TransactionNotFoundException;
import com.transactionexecution.api.domain.model.PageQuery;
import com.transactionexecution.api.domain.model.PageResult;
import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.model.TransactionFilter;
import com.transactionexecution.api.domain.model.TransactionStatus;
import com.transactionexecution.api.domain.model.TransactionType;
import com.transactionexecution.api.domain.port.TransactionReadRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchTransactionsUseCaseTest {

    private final TransactionReadRepository repository = mock(TransactionReadRepository.class);
    private final SearchTransactionsUseCase useCase = new SearchTransactionsUseCase(repository);

    private final Transaction transaction = Transaction.create("acc-1", TransactionType.CREDIT,
            new BigDecimal("100.00"), "MXN", null, Instant.now());

    @Test
    void searchDelegatesFilterAndPaging() {
        TransactionFilter filter = new TransactionFilter("acc-1", TransactionStatus.EXECUTED, TransactionType.CREDIT);
        PageQuery pageQuery = new PageQuery(2, 10);
        PageResult<Transaction> page = new PageResult<>(List.of(transaction), 2, 10, 21);
        when(repository.search(filter, pageQuery)).thenReturn(page);

        assertThat(useCase.search(filter, pageQuery)).isEqualTo(page);
    }

    @Test
    void getByIdReturnsTransaction() {
        when(repository.findById(transaction.getId())).thenReturn(Optional.of(transaction));

        assertThat(useCase.getById(transaction.getId())).isEqualTo(transaction);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getById(id))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining(id.toString());
    }
}
