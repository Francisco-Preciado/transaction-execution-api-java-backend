package com.transactionexecution.api.infrastructure.provider.dto;

/** Failed (HTTP 4xx/5xx) provider response, e.g. {@code INSUFFICIENT_FUNDS}. */
public record ProviderErrorResponse(String status, String code, String message) {
}
