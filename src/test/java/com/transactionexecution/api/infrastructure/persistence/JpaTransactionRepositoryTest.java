package com.transactionexecution.api.infrastructure.persistence;

import com.transactionexecution.api.domain.exception.DuplicateIdempotencyKeyException;
import com.transactionexecution.api.domain.model.PageQuery;
import com.transactionexecution.api.domain.model.PageResult;
import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.model.TransactionFilter;
import com.transactionexecution.api.domain.model.TransactionRequest;
import com.transactionexecution.api.domain.model.TransactionStatus;
import com.transactionexecution.api.domain.model.TransactionType;
import com.transactionexecution.api.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against a real PostgreSQL with the Flyway schema, so column types, constraints and
 * indexes are the production ones. Not transactional: each save commits like in production.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaTransactionRepository.class)
class JpaTransactionRepositoryTest extends PostgresContainerTest {

    private static final Instant T0 = Instant.parse("2025-03-15T10:00:00Z");

    @Autowired
    private JpaTransactionRepository repository;

    @Autowired
    private TransactionJpaRepository jpaRepository;

    @BeforeEach
    void cleanTable() {
        jpaRepository.deleteAllInBatch();
    }

    @Test
    void savesAndReadsBackAllFields() {
        Transaction pending = transaction("acc-1", TransactionType.CREDIT, "1500.50", T0);

        Transaction saved = repository.save(pending);

        assertThat(saved).usingRecursiveComparison().isEqualTo(pending);
        assertThat(repository.findById(pending.getId())).get().usingRecursiveComparison().isEqualTo(pending);
    }

    @Test
    void updatesOutcomeOfExistingTransaction() {
        Transaction transaction = repository.save(transaction("acc-1", TransactionType.DEBIT, "100.00", T0));

        transaction.markExecuted("txn-789", new BigDecimal("5400.00"));
        repository.save(transaction);

        Transaction stored = repository.findById(transaction.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TransactionStatus.EXECUTED);
        assertThat(stored.getProviderTransactionId()).isEqualTo("txn-789");
        assertThat(stored.getBalanceAfter()).isEqualByComparingTo("5400.00");
        assertThat(jpaRepository.count()).isEqualTo(1);
        assertThat(jpaRepository.findById(transaction.getId()).orElseThrow().getVersion()).isEqualTo(1L);
    }

    @Test
    void storesRejectionReason() {
        Transaction transaction = repository.save(transaction("acc-1", TransactionType.DEBIT, "100.00", T0));

        transaction.markRejected("INSUFFICIENT_FUNDS", "x".repeat(2_000));
        repository.save(transaction);

        Transaction stored = repository.findById(transaction.getId()).orElseThrow();
        assertThat(stored.getFailureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(stored.getFailureMessage()).hasSize(2_000);
    }

    @Test
    void findByIdReturnsEmptyWhenMissing() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void searchWithoutFilterReturnsNewestFirst() {
        Transaction oldest = repository.save(transaction("acc-1", TransactionType.CREDIT, "10.00", T0));
        Transaction middle = repository.save(transaction("acc-2", TransactionType.DEBIT, "20.00", T0.plusSeconds(1)));
        Transaction newest = repository.save(transaction("acc-1", TransactionType.DEBIT, "30.00", T0.plusSeconds(2)));

        PageResult<Transaction> page = repository.search(TransactionFilter.none(), PageQuery.first());

        assertThat(page.items()).extracting(Transaction::getId)
                .containsExactly(newest.getId(), middle.getId(), oldest.getId());
        assertThat(page.totalItems()).isEqualTo(3);
    }

    @Test
    void searchFiltersByAccountStatusAndType() {
        Transaction executedCredit = transaction("acc-1", TransactionType.CREDIT, "10.00", T0);
        executedCredit.markExecuted("txn-1", new BigDecimal("10.00"));
        repository.save(executedCredit);

        Transaction rejectedDebit = transaction("acc-1", TransactionType.DEBIT, "20.00", T0.plusSeconds(1));
        rejectedDebit.markRejected("INSUFFICIENT_FUNDS", "no balance");
        repository.save(rejectedDebit);

        repository.save(transaction("acc-2", TransactionType.CREDIT, "30.00", T0.plusSeconds(2)));

        assertThat(searchIds(new TransactionFilter("acc-1", null, null)))
                .containsExactly(rejectedDebit.getId(), executedCredit.getId());
        assertThat(searchIds(new TransactionFilter(null, TransactionStatus.EXECUTED, null)))
                .containsExactly(executedCredit.getId());
        assertThat(searchIds(new TransactionFilter(null, null, TransactionType.DEBIT)))
                .containsExactly(rejectedDebit.getId());
        assertThat(searchIds(new TransactionFilter("acc-1", TransactionStatus.REJECTED, TransactionType.DEBIT)))
                .containsExactly(rejectedDebit.getId());
        assertThat(searchIds(new TransactionFilter("acc-2", TransactionStatus.EXECUTED, null))).isEmpty();
    }

    @Test
    void searchPaginates() {
        for (int i = 0; i < 5; i++) {
            repository.save(transaction("acc-1", TransactionType.CREDIT, "10.00", T0.plusSeconds(i)));
        }

        PageResult<Transaction> first = repository.search(TransactionFilter.none(), new PageQuery(0, 2));
        PageResult<Transaction> last = repository.search(TransactionFilter.none(), new PageQuery(2, 2));
        PageResult<Transaction> beyond = repository.search(TransactionFilter.none(), new PageQuery(3, 2));

        assertThat(first.items()).hasSize(2);
        assertThat(first.totalItems()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(last.items()).hasSize(1);
        assertThat(last.items().getFirst().getCreatedAt()).isEqualTo(T0);
        assertThat(beyond.items()).isEmpty();
    }

    @Test
    void findsTransactionByIdempotencyKey() {
        Transaction keyed = repository.save(keyedTransaction("key-1"));
        repository.save(transaction("acc-1", TransactionType.CREDIT, "10.00", T0));

        assertThat(repository.findByIdempotencyKey("key-1")).get().usingRecursiveComparison().isEqualTo(keyed);
        assertThat(repository.findByIdempotencyKey("key-2")).isEmpty();
    }

    @Test
    void duplicateIdempotencyKeyIsRejectedByTheDatabase() {
        repository.save(keyedTransaction("key-1"));

        assertThatThrownBy(() -> repository.save(keyedTransaction("key-1")))
                .isInstanceOf(DuplicateIdempotencyKeyException.class)
                .hasMessageContaining("key-1");
        assertThat(jpaRepository.count()).isEqualTo(1);
    }

    @Test
    void transactionsWithoutIdempotencyKeyNeverClash() {
        repository.save(transaction("acc-1", TransactionType.CREDIT, "10.00", T0));
        repository.save(transaction("acc-1", TransactionType.CREDIT, "10.00", T0));

        assertThat(jpaRepository.count()).isEqualTo(2);
    }

    @Test
    void updatingAKeyedTransactionKeepsItsKey() {
        Transaction transaction = repository.save(keyedTransaction("key-1"));

        transaction.markExecuted("txn-789", new BigDecimal("5400.00"));
        repository.save(transaction);

        assertThat(repository.findByIdempotencyKey("key-1")).get()
                .extracting(Transaction::getStatus).isEqualTo(TransactionStatus.EXECUTED);
    }

    private static Transaction keyedTransaction(String idempotencyKey) {
        return Transaction.create(new TransactionRequest("acc-1", TransactionType.DEBIT, new BigDecimal("100.00"),
                "MXN", "test"), idempotencyKey, T0);
    }

    private java.util.List<UUID> searchIds(TransactionFilter filter) {
        return repository.search(filter, PageQuery.first()).items().stream().map(Transaction::getId).toList();
    }

    private static Transaction transaction(String accountId, TransactionType type, String amount, Instant createdAt) {
        return Transaction.create(accountId, type, new BigDecimal(amount), "MXN", "test", createdAt);
    }
}
