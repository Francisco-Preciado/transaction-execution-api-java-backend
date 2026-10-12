package com.transactionexecution.api.infrastructure.provider.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Successful (HTTP 200) provider response. {@code status} is kept as a String so an unknown value
 * is reported as an unexpected response instead of failing deserialization.
 */
public record ProviderExecuteResponse(String transactionId, String status, BigDecimal balance, Instant executedAt) {

    public static final String APPROVED = "APPROVED";
}
