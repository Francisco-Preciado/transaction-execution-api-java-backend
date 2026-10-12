package com.transactionexecution.api.infrastructure.provider;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.transactionexecution.api.domain.exception.ProviderException;
import com.transactionexecution.api.domain.model.ProviderResult;
import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.model.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.transactionexecution.api.infrastructure.provider.HttpTransactionProvider.EXECUTE_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTransactionProviderTest {

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);

    private HttpTransactionProvider client;
    private Transaction transaction;

    @BeforeEach
    void setUp() {
        ProviderProperties properties =
                new ProviderProperties(URI.create(provider.baseUrl()), Duration.ofSeconds(1), READ_TIMEOUT);
        client = new HttpTransactionProvider(ProviderClientConfig.providerRestClient(RestClient.builder(), properties));
        transaction = Transaction.create("acc-123456", TransactionType.CREDIT, new BigDecimal("1500.00"),
                "MXN", "Transferencia recibida", Instant.parse("2025-03-15T10:30:00Z"));
    }

    @Test
    void approvedResponseBecomesApproved() {
        provider.stubFor(post(EXECUTE_PATH).willReturn(okJson("""
                {"transactionId": "txn-789", "status": "APPROVED", "balance": 5500.00,
                 "executedAt": "2025-03-15T10:30:00Z"}""")));

        ProviderResult result = client.execute(transaction);

        assertThat(result).isEqualTo(new ProviderResult.Approved("txn-789", new BigDecimal("5500.00"),
                Instant.parse("2025-03-15T10:30:00Z")));
    }

    @Test
    void sendsContractBodyAndIdempotencyKey() {
        provider.stubFor(post(EXECUTE_PATH).willReturn(okJson("""
                {"transactionId": "txn-789", "status": "APPROVED", "balance": 5500.00}""")));

        client.execute(transaction);

        provider.verify(postRequestedFor(urlEqualTo(EXECUTE_PATH))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader(HttpTransactionProvider.IDEMPOTENCY_KEY_HEADER, equalTo(transaction.getId().toString()))
                .withRequestBody(equalToJson("""
                        {"accountId": "acc-123456", "type": "CREDIT", "amount": 1500.00, "currency": "MXN"}""")));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 402, 404, 409, 422})
    void clientErrorBecomesRejectedWithProviderReason(int status) {
        provider.stubFor(post(EXECUTE_PATH).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"status": "REJECTED", "code": "INSUFFICIENT_FUNDS",
                         "message": "The account does not have enough balance to complete the transaction"}""")));

        ProviderResult result = client.execute(transaction);

        assertThat(result).isEqualTo(new ProviderResult.Rejected("INSUFFICIENT_FUNDS",
                "The account does not have enough balance to complete the transaction"));
    }

    @Test
    void clientErrorWithoutBodyStillBecomesRejected() {
        provider.stubFor(post(EXECUTE_PATH).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "text/plain").withBody("Forbidden")));

        ProviderResult result = client.execute(transaction);

        assertThat(result).isEqualTo(new ProviderResult.Rejected(HttpTransactionProvider.PROVIDER_REJECTED,
                "Provider rejected the transaction with HTTP 403"));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void serverErrorIsProviderException(int status) {
        provider.stubFor(post(EXECUTE_PATH).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"status": "REJECTED", "code": "INTERNAL_ERROR", "message": "boom"}""")));

        assertThatThrownBy(() -> client.execute(transaction))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("HTTP " + status)
                .hasMessageContaining("INTERNAL_ERROR");
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429})
    void transientClientErrorIsProviderException(int status) {
        provider.stubFor(post(EXECUTE_PATH).willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> client.execute(transaction))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("HTTP " + status);
    }

    @Test
    void timeoutIsProviderException() {
        provider.stubFor(post(EXECUTE_PATH).willReturn(okJson("""
                {"transactionId": "txn-789", "status": "APPROVED", "balance": 5500.00}""")
                .withFixedDelay((int) READ_TIMEOUT.toMillis() * 3)));

        assertThatThrownBy(() -> client.execute(transaction))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("Provider call failed");
    }

    @Test
    void connectionResetIsProviderException() {
        provider.stubFor(post(EXECUTE_PATH).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> client.execute(transaction)).isInstanceOf(ProviderException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"transactionId\": \"txn-1\", \"status\": \"PENDING\", \"balance\": 10.00}",
            "{\"transactionId\": \"txn-1\", \"status\": \"APPROVED\"}",
            "{\"status\": \"APPROVED\", \"balance\": 10.00}",
            "not json"
    })
    void unexpectedSuccessBodyIsProviderException(String body) {
        provider.stubFor(post(EXECUTE_PATH).willReturn(okJson(body)));

        assertThatThrownBy(() -> client.execute(transaction)).isInstanceOf(ProviderException.class);
    }
}
