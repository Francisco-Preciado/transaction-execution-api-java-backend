package com.transactionexecution.api.infrastructure.config;

import com.transactionexecution.api.application.ExecuteTransactionUseCase;
import com.transactionexecution.api.application.SearchTransactionsUseCase;
import com.transactionexecution.api.domain.port.TransactionProvider;
import com.transactionexecution.api.domain.port.TransactionReadRepository;
import com.transactionexecution.api.domain.port.TransactionWriteRepository;
import com.transactionexecution.api.domain.rule.DebitLimitRule;
import com.transactionexecution.api.domain.rule.MinimumAmountRule;
import com.transactionexecution.api.domain.rule.SupportedCurrencyRule;
import com.transactionexecution.api.domain.rule.TransactionRulesValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/**
 * Wires the framework-free domain and application classes into Spring.
 */
@Configuration
@EnableConfigurationProperties(TransactionRulesProperties.class)
class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionRulesValidator transactionRulesValidator(TransactionRulesProperties rules) {
        return new TransactionRulesValidator(List.of(
                new SupportedCurrencyRule(rules.supportedCurrencies()),
                new MinimumAmountRule(rules.minimumAmount()),
                new DebitLimitRule(rules.maximumDebitAmount())));
    }

    @Bean
    ExecuteTransactionUseCase executeTransactionUseCase(TransactionRulesValidator rulesValidator,
                                                        TransactionWriteRepository transactionRepository,
                                                        TransactionProvider transactionProvider,
                                                        Clock clock) {
        return new ExecuteTransactionUseCase(rulesValidator, transactionRepository, transactionProvider, clock);
    }

    @Bean
    SearchTransactionsUseCase searchTransactionsUseCase(TransactionReadRepository transactionRepository) {
        return new SearchTransactionsUseCase(transactionRepository);
    }
}
