package com.transactionexecution.api.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a real PostgreSQL. The container is started once per JVM and
 * shared by every subclass (Testcontainers' Ryuk removes it when the JVM exits). Requires Docker.
 */
public abstract class PostgresContainerTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }
}
