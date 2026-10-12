package com.transactionexecution.api;

import com.transactionexecution.api.domain.port.TransactionProvider;
import com.transactionexecution.api.infrastructure.provider.ResilientTransactionProvider;
import com.transactionexecution.api.support.PostgresContainerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the full application context starts against a real PostgreSQL (Flyway + JPA).
 */
@SpringBootTest
class TransactionExecutionApiApplicationTests extends PostgresContainerTest {

    @Autowired
    private TransactionProvider transactionProvider;

    @Test
    void contextLoads() {
        assertThat(transactionProvider).isInstanceOf(ResilientTransactionProvider.class);
    }
}
