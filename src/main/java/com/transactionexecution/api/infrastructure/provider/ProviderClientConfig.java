package com.transactionexecution.api.infrastructure.provider;

import com.transactionexecution.api.domain.port.TransactionProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Wires the provider adapter. Only the resilient decorator is exposed as the
 * {@link TransactionProvider} bean, so nothing can call the raw HTTP adapter by mistake.
 */
@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
class ProviderClientConfig {

    /** Name of the Resilience4j retry and circuit breaker instances in application.yml. */
    static final String RESILIENCE_INSTANCE = "provider";

    @Bean
    TransactionProvider transactionProvider(RestClient.Builder restClientBuilder, ProviderProperties properties,
                                            CircuitBreakerRegistry circuitBreakerRegistry,
                                            RetryRegistry retryRegistry) {
        return new ResilientTransactionProvider(
                new HttpTransactionProvider(providerRestClient(restClientBuilder, properties)),
                circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE),
                retryRegistry.retry(RESILIENCE_INSTANCE));
    }

    /**
     * HTTP/1.1 is forced: the JDK client otherwise attempts an h2c upgrade on every plain-HTTP
     * request, which some servers answer by resetting the stream.
     */
    static RestClient providerRestClient(RestClient.Builder builder, ProviderProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
