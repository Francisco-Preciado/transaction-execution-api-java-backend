package com.transactionexecution.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jayway.jsonpath.JsonPath;
import com.transactionexecution.api.support.PostgresContainerTest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end: full application context, real PostgreSQL (Flyway schema) and the provider mocked
 * over real HTTP with WireMock. Exercises the whole flow including retries and persistence.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionApiEndToEndTest extends PostgresContainerTest {

    private static final String EXECUTE_PATH = "/provider/v1/execute";
    private static final String CREDIT_BODY = """
            {"accountId": "acc-123456", "type": "CREDIT", "amount": 1500.00,
             "currency": "MXN", "description": "Transferencia recibida"}""";
    private static final String DEBIT_BODY = """
            {"accountId": "acc-123456", "type": "DEBIT", "amount": 200.00, "currency": "MXN"}""";

    private static final WireMockServer provider = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        provider.start();
    }

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("provider.base-url", provider::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void reset() {
        provider.resetAll();
        jdbcTemplate.execute("TRUNCATE TABLE transactions");
        circuitBreakerRegistry.circuitBreaker("provider").reset();
    }

    @AfterAll
    static void stopProvider() {
        provider.stop();
    }

    @Test
    void approvedTransactionIsExecutedPersistedAndQueryable() throws Exception {
        provider.stubFor(post(EXECUTE_PATH).willReturn(okJson("""
                {"transactionId": "txn-789", "status": "APPROVED", "balance": 5500.00,
                 "executedAt": "2025-03-15T10:30:00Z"}""")));

        String id = idOf(postTransaction(CREDIT_BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.providerTransactionId").value("txn-789"))
                .andExpect(jsonPath("$.balanceAfter").value(5500.00)));

        mockMvc.perform(get("/transactions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.balanceAfter").value(5500.00))
                .andExpect(jsonPath("$.description").value("Transferencia recibida"));

        provider.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH))
                .withHeader("Idempotency-Key", equalTo(id))
                .withRequestBody(equalToJson("""
                        {"accountId": "acc-123456", "type": "CREDIT", "amount": 1500.00, "currency": "MXN"}""")));
    }

    @Test
    void providerRejectionIsRecordedAsRejected() throws Exception {
        provider.stubFor(post(EXECUTE_PATH).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"status": "REJECTED", "code": "INSUFFICIENT_FUNDS",
                         "message": "The account does not have enough balance to complete the transaction"}""")));

        String id = idOf(postTransaction(DEBIT_BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.failureCode").value("INSUFFICIENT_FUNDS")));

        mockMvc.perform(get("/transactions/{id}", id))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.failureMessage")
                        .value("The account does not have enough balance to complete the transaction"));
        provider.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
    }

    @Test
    void transientProviderErrorIsRetriedWithSameIdempotencyKey() throws Exception {
        provider.stubFor(post(EXECUTE_PATH).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        provider.stubFor(post(EXECUTE_PATH).inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson("""
                        {"transactionId": "txn-790", "status": "APPROVED", "balance": 5300.00}""")));

        String id = idOf(postTransaction(DEBIT_BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED")));

        provider.verify(2, postRequestedFor(urlEqualTo(EXECUTE_PATH)).withHeader("Idempotency-Key", equalTo(id)));
    }

    @Test
    void providerDownIsRecordedAsFailedAfterRetries() throws Exception {
        provider.stubFor(post(EXECUTE_PATH).willReturn(aResponse().withStatus(503)));

        String id = idOf(postTransaction(DEBIT_BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("PROVIDER_UNAVAILABLE")));

        provider.verify(3, postRequestedFor(urlEqualTo(EXECUTE_PATH)).withHeader("Idempotency-Key", equalTo(id)));
        mockMvc.perform(get("/transactions/{id}", id)).andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void businessRuleViolationIsRejectedBeforeProviderAndNotStored() throws Exception {
        postTransaction("""
                {"accountId": "acc-123456", "type": "DEBIT", "amount": 10000.01, "currency": "MXN"}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEBIT_LIMIT_EXCEEDED"))
                // limits come from application.yml, so this also checks they bind without losing scale
                .andExpect(jsonPath("$.detail")
                        .value("DEBIT amount cannot exceed 10000.00 per transaction, got 10000.01"));

        provider.verify(0, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Long.class)).isZero();
    }

    @Test
    void retryWithSameIdempotencyKeyReturnsOriginalWithoutExecutingAgain() throws Exception {
        provider.stubFor(post(EXECUTE_PATH).willReturn(okJson("""
                {"transactionId": "txn-791", "status": "APPROVED", "balance": 5300.00}""")));

        String id = idOf(postTransaction(DEBIT_BODY, "client-key-1")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed")));

        postTransaction(DEBIT_BODY, "client-key-1")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.balanceAfter").value(5300.00));

        provider.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Long.class)).isOne();
    }

    @Test
    void idempotencyKeyReusedForDifferentRequestIsRefused() throws Exception {
        provider.stubFor(post(EXECUTE_PATH).willReturn(okJson("""
                {"transactionId": "txn-792", "status": "APPROVED", "balance": 5300.00}""")));
        postTransaction(DEBIT_BODY, "client-key-2").andExpect(status().isCreated());

        postTransaction(DEBIT_BODY.replace("200.00", "300.00"), "client-key-2")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        provider.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Long.class)).isOne();
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKeyExecuteOnce() throws Exception {
        // Slow provider, so the duplicates arrive while the first request is still PENDING.
        provider.stubFor(post(EXECUTE_PATH).willReturn(okJson("""
                {"transactionId": "txn-793", "status": "APPROVED", "balance": 5300.00}""").withFixedDelay(500)));
        int requests = 5;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        List<Future<Integer>> statuses = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                statuses.add(pool.submit(() -> {
                    start.await();
                    return postTransaction(DEBIT_BODY, "client-key-3").andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                codes.add(status.get(30, TimeUnit.SECONDS));
            }

            assertThat(codes).containsOnlyOnce(201).containsOnly(201, 409);
        } finally {
            pool.shutdownNow();
        }
        provider.verify(1, postRequestedFor(urlEqualTo(EXECUTE_PATH)));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Long.class)).isOne();
    }

    @Test
    void searchFiltersAndPaginatesStoredTransactions() throws Exception {
        provider.stubFor(post(EXECUTE_PATH).willReturn(okJson("""
                {"transactionId": "txn-1", "status": "APPROVED", "balance": 1000.00}""")));
        for (int i = 0; i < 3; i++) {
            postTransaction(CREDIT_BODY).andExpect(status().isCreated());
        }
        postTransaction(CREDIT_BODY.replace("acc-123456", "acc-other")).andExpect(status().isCreated());

        mockMvc.perform(get("/transactions").param("accountId", "acc-123456").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/transactions").param("status", "EXECUTED").param("type", "CREDIT"))
                .andExpect(jsonPath("$.totalItems").value(4));
        mockMvc.perform(get("/transactions").param("type", "DEBIT"))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void publishesOpenApiSpec() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Transaction Execution API"))
                .andExpect(jsonPath("$.paths['/transactions'].post").exists())
                .andExpect(jsonPath("$.paths['/transactions'].get").exists())
                .andExpect(jsonPath("$.paths['/transactions/{id}'].get").exists());
    }

    private ResultActions postTransaction(String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions postTransaction(String body, String idempotencyKey) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
