package com.transactionexecution.api.application;

import com.transactionexecution.api.domain.exception.BusinessRuleViolationException;
import com.transactionexecution.api.domain.exception.DuplicateIdempotencyKeyException;
import com.transactionexecution.api.domain.exception.IdempotencyKeyReusedException;
import com.transactionexecution.api.domain.exception.ProviderException;
import com.transactionexecution.api.domain.exception.RequestInProgressException;
import com.transactionexecution.api.domain.model.ProviderResult;
import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.model.TransactionRequest;
import com.transactionexecution.api.domain.model.TransactionStatus;
import com.transactionexecution.api.domain.port.TransactionProvider;
import com.transactionexecution.api.domain.port.TransactionWriteRepository;
import com.transactionexecution.api.domain.rule.TransactionRulesValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Optional;

/**
 * Executes a transaction: validate → persist PENDING → call provider → persist final status.
 *
 * <p>The PENDING record is written before the provider call, so a crash mid-call leaves a trace
 * that can be reconciled instead of a transaction the provider executed but we never stored.
 * No database transaction spans the provider call: each save is short, and a slow provider never
 * holds a database connection.
 *
 * <p>When the client sends an idempotency key, a repeated request returns the transaction stored
 * the first time instead of executing it again. The unique constraint on the key decides the
 * winner when two identical requests arrive at the same time.
 */
public class ExecuteTransactionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExecuteTransactionUseCase.class);

    private final TransactionRulesValidator rulesValidator;
    private final TransactionWriteRepository transactionRepository;
    private final TransactionProvider transactionProvider;
    private final Clock clock;

    /**
     * Outcome of {@link #execute}: the stored transaction, and whether it was executed by an
     * earlier request with the same idempotency key ({@code replayed}) rather than by this one.
     */
    public record Execution(Transaction transaction, boolean replayed) {
    }

    public ExecuteTransactionUseCase(TransactionRulesValidator rulesValidator,
                                     TransactionWriteRepository transactionRepository,
                                     TransactionProvider transactionProvider,
                                     Clock clock) {
        this.rulesValidator = rulesValidator;
        this.transactionRepository = transactionRepository;
        this.transactionProvider = transactionProvider;
        this.clock = clock;
    }

    /**
     * @param idempotencyKey the client's key for this request, or {@code null} to always execute
     * @return the stored transaction with its final status: EXECUTED, REJECTED or FAILED
     * @throws BusinessRuleViolationException when the request breaks a business rule; nothing is
     *                                        persisted and the provider is not called
     * @throws IdempotencyKeyReusedException  when the key was already used for a different request
     * @throws RequestInProgressException     when the first request with this key has not finished yet
     */
    public Execution execute(TransactionRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<Transaction> previous = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (previous.isPresent()) {
                return replay(previous.get(), request, idempotencyKey);
            }
        }

        rulesValidator.validate(request);

        Transaction transaction;
        try {
            transaction = transactionRepository.save(Transaction.create(request, idempotencyKey, clock.instant()));
        } catch (DuplicateIdempotencyKeyException e) {
            // A concurrent request with the same key stored its transaction first.
            Transaction winner = transactionRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            return replay(winner, request, idempotencyKey);
        }
        applyProviderOutcome(transaction);
        return new Execution(transactionRepository.save(transaction), false);
    }

    private static Execution replay(Transaction previous, TransactionRequest request, String idempotencyKey) {
        if (!previous.matches(request)) {
            throw new IdempotencyKeyReusedException(idempotencyKey);
        }
        if (previous.getStatus() == TransactionStatus.PENDING) {
            throw new RequestInProgressException(idempotencyKey);
        }
        log.info("Replaying transaction {} for idempotency key {}", previous.getId(), idempotencyKey);
        return new Execution(previous, true);
    }

    private void applyProviderOutcome(Transaction transaction) {
        try {
            switch (transactionProvider.execute(transaction)) {
                case ProviderResult.Approved approved ->
                        transaction.markExecuted(approved.providerTransactionId(), approved.balance());
                case ProviderResult.Rejected rejected -> {
                    log.info("Transaction {} rejected by provider: {}", transaction.getId(), rejected.code());
                    transaction.markRejected(rejected.code(), rejected.message());
                }
            }
        } catch (ProviderException e) {
            log.warn("Transaction {} failed, provider gave no definitive answer: {}",
                    transaction.getId(), e.getMessage());
            transaction.markFailed(e.getCode(), e.getMessage());
        }
    }
}
