package com.transactionexecution.api.infrastructure.provider;

import com.transactionexecution.api.domain.exception.ProviderException;
import com.transactionexecution.api.domain.model.ProviderResult;
import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.port.TransactionProvider;
import com.transactionexecution.api.infrastructure.provider.dto.ProviderErrorResponse;
import com.transactionexecution.api.infrastructure.provider.dto.ProviderExecuteRequest;
import com.transactionexecution.api.infrastructure.provider.dto.ProviderExecuteResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.Set;

/**
 * HTTP adapter for the provider's {@code POST /provider/v1/execute}. Translates every outcome into
 * the domain's terms:
 * <ul>
 *   <li>HTTP 200 + APPROVED → {@link ProviderResult.Approved}</li>
 *   <li>HTTP 4xx → {@link ProviderResult.Rejected}: a definitive "no" (e.g. INSUFFICIENT_FUNDS)</li>
 *   <li>HTTP 5xx, 408, 429, timeouts, I/O errors, malformed responses → {@link ProviderException}:
 *       no definitive answer, so the call may be retried</li>
 * </ul>
 * Resilience (retries, circuit breaker) is added by {@link ResilientTransactionProvider}.
 */
public class HttpTransactionProvider implements TransactionProvider {

    static final String EXECUTE_PATH = "/provider/v1/execute";
    /** Same key on every retry, so the provider can recognize and ignore duplicates. */
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String PROVIDER_REJECTED = "PROVIDER_REJECTED";

    /** 4xx statuses that mean "try again later", not "rejected". */
    private static final Set<HttpStatus> TRANSIENT_CLIENT_ERRORS =
            Set.of(HttpStatus.REQUEST_TIMEOUT, HttpStatus.TOO_MANY_REQUESTS);

    private final RestClient restClient;

    public HttpTransactionProvider(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ProviderResult execute(Transaction transaction) {
        try {
            return restClient.post()
                    .uri(EXECUTE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_KEY_HEADER, transaction.getId().toString())
                    .body(ProviderExecuteRequest.from(transaction))
                    .exchange((request, response) -> toResult(response));
        } catch (RestClientException e) {
            // Timeouts, connection errors and unreadable responses
            throw new ProviderException("Provider call failed: " + e.getMessage(), e);
        }
    }

    private ProviderResult toResult(ConvertibleClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is2xxSuccessful()) {
            return toApproved(response.bodyTo(ProviderExecuteResponse.class));
        }
        ProviderErrorResponse error = readError(response);
        if (status.is4xxClientError() && !TRANSIENT_CLIENT_ERRORS.contains(HttpStatus.resolve(status.value()))) {
            return toRejected(status, error);
        }
        throw new ProviderException("Provider answered HTTP %d%s".formatted(status.value(),
                error != null && error.code() != null ? " (" + error.code() + ")" : ""));
    }

    private static ProviderResult toApproved(ProviderExecuteResponse body) {
        if (body == null || !ProviderExecuteResponse.APPROVED.equals(body.status())
                || body.transactionId() == null || body.balance() == null) {
            throw new ProviderException("Unexpected provider response: " + body);
        }
        return new ProviderResult.Approved(body.transactionId(), body.balance(), body.executedAt());
    }

    private static ProviderResult toRejected(HttpStatusCode status, ProviderErrorResponse error) {
        String code = error != null && error.code() != null ? error.code() : PROVIDER_REJECTED;
        String message = error != null && error.message() != null
                ? error.message()
                : "Provider rejected the transaction with HTTP " + status.value();
        return new ProviderResult.Rejected(code, message);
    }

    /** The error body is optional; a missing or non-JSON body must not hide the HTTP status. */
    private static ProviderErrorResponse readError(ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(ProviderErrorResponse.class);
        } catch (RestClientException e) {
            return null;
        }
    }
}
