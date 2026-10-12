package com.transactionexecution.api.infrastructure.web.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class HealthService {

    private final String serviceName;
    private final String version;

    public HealthService(@Value("${spring.application.name}") String serviceName,
                         @Value("${app.version}") String version) {
        this.serviceName = serviceName;
        this.version = version;
    }

    public HealthResponse check() {
        return new HealthResponse("UP", serviceName, version, Instant.now());
    }
}
