package com.transactionexecution.api.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

interface TransactionJpaRepository extends JpaRepository<TransactionEntity, UUID>,
        JpaSpecificationExecutor<TransactionEntity> {

    Optional<TransactionEntity> findByIdempotencyKey(String idempotencyKey);
}
