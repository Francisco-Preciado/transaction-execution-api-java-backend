package com.transactionexecution.api.infrastructure.web.health;

import java.time.Instant;

public record HealthResponse(String status, String service, String version, Instant timestamp) {
}
