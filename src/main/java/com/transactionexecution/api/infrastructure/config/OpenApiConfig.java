package com.transactionexecution.api.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI openApi(@Value("${app.version}") String version) {
        return new OpenAPI().info(new Info()
                .title("Transaction Execution API")
                .version(version)
                .description("""
                        Executes CREDIT and DEBIT transactions against an external provider, stores the \
                        outcome and exposes it for querying.

                        **POST outcomes:** 201 whenever a transaction was recorded; its `status` is \
                        `EXECUTED`, `REJECTED` (provider said no) or `FAILED` (provider gave no answer). \
                        422 when a business rule is broken (nothing recorded). Errors are RFC 9457 problem \
                        details with a stable `code`.

                        **Demo accounts (docker compose):** `acc-no-funds`, `acc-blocked`, \
                        `acc-provider-error`, `acc-slow`; any other account is approved."""));
    }
}
