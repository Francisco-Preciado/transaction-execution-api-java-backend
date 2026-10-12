package com.transactionexecution.api.infrastructure.provider;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Connection settings for the external provider ({@code provider.*} in application.yml).
 * Retry and circuit breaker settings live under {@code resilience4j.*}.
 */
@Validated
@ConfigurationProperties("provider")
public record ProviderProperties(
        @NotNull URI baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout) {
}
