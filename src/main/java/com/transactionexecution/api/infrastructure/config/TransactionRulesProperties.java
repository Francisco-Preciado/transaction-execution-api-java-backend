package com.transactionexecution.api.infrastructure.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Business rule limits ({@code transaction.rules.*}). Defaults are the challenge's values.
 */
@Validated
@ConfigurationProperties("transaction.rules")
public record TransactionRulesProperties(
        @NotNull @DefaultValue("1.00") BigDecimal minimumAmount,
        @NotNull @DefaultValue("10000.00") BigDecimal maximumDebitAmount,
        @NotEmpty @DefaultValue("MXN") Set<String> supportedCurrencies) {
}
