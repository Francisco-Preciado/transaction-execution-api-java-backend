package com.transactionexecution.api.infrastructure.web.transaction;

import com.transactionexecution.api.application.ExecuteTransactionUseCase;
import com.transactionexecution.api.application.ExecuteTransactionUseCase.Execution;
import com.transactionexecution.api.application.SearchTransactionsUseCase;
import com.transactionexecution.api.domain.exception.BusinessRuleViolationException;
import com.transactionexecution.api.domain.exception.IdempotencyKeyReusedException;
import com.transactionexecution.api.domain.exception.RequestInProgressException;
import com.transactionexecution.api.domain.exception.TransactionNotFoundException;
import com.transactionexecution.api.domain.model.PageQuery;
import com.transactionexecution.api.domain.model.PageResult;
import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.model.TransactionFilter;
import com.transactionexecution.api.domain.model.TransactionRequest;
import com.transactionexecution.api.domain.model.TransactionStatus;
import com.transactionexecution.api.domain.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

    private static final Instant NOW = Instant.parse("2025-03-15T10:30:00Z");
    private static final String VALID_BODY = """
            {"accountId": "acc-123456", "type": "CREDIT", "amount": 1500.00,
             "currency": "MXN", "description": "Transferencia recibida"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExecuteTransactionUseCase executeTransaction;

    @MockitoBean
    private SearchTransactionsUseCase searchTransactions;

    // ---- POST /transactions ----

    @Test
    void executedTransactionReturns201WithLocationAndBody() throws Exception {
        Transaction tx = transaction();
        tx.markExecuted("txn-789", new BigDecimal("5500.00"));
        when(executeTransaction.execute(any(), any())).thenReturn(new Execution(tx, false));

        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/transactions/" + tx.getId())))
                .andExpect(jsonPath("$.id").value(tx.getId().toString()))
                .andExpect(jsonPath("$.accountId").value("acc-123456"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.amount").value(1500.00))
                .andExpect(jsonPath("$.currency").value("MXN"))
                .andExpect(jsonPath("$.description").value("Transferencia recibida"))
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.providerTransactionId").value("txn-789"))
                .andExpect(jsonPath("$.balanceAfter").value(5500.00))
                .andExpect(jsonPath("$.createdAt").value("2025-03-15T10:30:00Z"));

        verify(executeTransaction).execute(new TransactionRequest("acc-123456", TransactionType.CREDIT,
                new BigDecimal("1500.00"), "MXN", "Transferencia recibida"), null);
    }

    @Test
    void providerRejectionReturns201WithRejectedStatus() throws Exception {
        Transaction tx = transaction();
        tx.markRejected("INSUFFICIENT_FUNDS", "The account does not have enough balance");
        when(executeTransaction.execute(any(), any())).thenReturn(new Execution(tx, false));

        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.failureCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.balanceAfter").value(nullValue()));
    }

    @Test
    void businessRuleViolationReturns422() throws Exception {
        when(executeTransaction.execute(any(), any())).thenThrow(new BusinessRuleViolationException("DEBIT_LIMIT_EXCEEDED",
                "DEBIT amount cannot exceed 10000.00 per transaction, got 10000.01"));

        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DEBIT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.title").value("Business rule violation"))
                .andExpect(jsonPath("$.detail").value("DEBIT amount cannot exceed 10000.00 per transaction, got 10000.01"));
    }

    @Test
    void idempotencyKeyHeaderIsPassedToUseCase() throws Exception {
        when(executeTransaction.execute(any(), any())).thenReturn(new Execution(transaction(), false));

        mockMvc.perform(post("/transactions").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        verify(executeTransaction).execute(any(), eq("key-1"));
    }

    @Test
    void replayedRequestReturns200WithOriginalTransaction() throws Exception {
        Transaction tx = transaction();
        tx.markExecuted("txn-789", new BigDecimal("5500.00"));
        when(executeTransaction.execute(any(), any())).thenReturn(new Execution(tx, true));

        mockMvc.perform(post("/transactions").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", endsWith("/transactions/" + tx.getId())))
                .andExpect(jsonPath("$.id").value(tx.getId().toString()))
                .andExpect(jsonPath("$.status").value("EXECUTED"));
    }

    @Test
    void idempotencyKeyReusedForDifferentRequestReturns422() throws Exception {
        when(executeTransaction.execute(any(), any())).thenThrow(new IdempotencyKeyReusedException("key-1"));

        mockMvc.perform(post("/transactions").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void requestInProgressReturns409() throws Exception {
        when(executeTransaction.execute(any(), any())).thenThrow(new RequestInProgressException("key-1"));

        mockMvc.perform(post("/transactions").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "ñ-not-ascii"})
    void invalidIdempotencyKeyReturns400(String key) throws Exception {
        mockMvc.perform(post("/transactions").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("idempotencyKey"));

        verifyNoInteractions(executeTransaction);
    }

    @Test
    void idempotencyKeyLongerThan64Returns400() throws Exception {
        mockMvc.perform(post("/transactions").header("Idempotency-Key", "k".repeat(65))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("idempotencyKey"));

        verifyNoInteractions(executeTransaction);
    }

    @Test
    void missingFieldsReturn400WithEveryInvalidField() throws Exception {
        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder(
                        "accountId", "type", "amount", "currency")));

        verifyNoInteractions(executeTransaction);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"accountId\": \"acc-1\", \"type\": \"CREDIT\", \"amount\": 10.001, \"currency\": \"MXN\"} | amount",
            "{\"accountId\": \"   \", \"type\": \"CREDIT\", \"amount\": 10.00, \"currency\": \"MXN\"}  | accountId",
            "{\"accountId\": \"acc-1\", \"type\": \"CREDIT\", \"amount\": 10.00, \"currency\": \"MXNN\"} | currency"
    })
    void invalidFieldReturns400(String body, String field) throws Exception {
        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem(field)));

        verifyNoInteractions(executeTransaction);
    }

    @Test
    void descriptionLongerThan255Returns400() throws Exception {
        String body = """
                {"accountId": "acc-1", "type": "CREDIT", "amount": 10.00, "currency": "MXN", "description": "%s"}"""
                .formatted("x".repeat(256));

        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"accountId\": \"acc-1\", \"type\": \"TRANSFER\", \"amount\": 10.00, \"currency\": \"MXN\"}",
            "{\"accountId\": \"acc-1\", \"type\": \"CREDIT\", \"amount\": \"ten\", \"currency\": \"MXN\"}",
            "not json"
    })
    void malformedBodyReturns400(String body) throws Exception {
        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(executeTransaction);
    }

    @Test
    void unexpectedErrorReturns500WithoutInternals() throws Exception {
        when(executeTransaction.execute(any(), any())).thenThrow(new IllegalStateException("db password is hunter2"));

        mockMvc.perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }

    // ---- GET /transactions ----

    @Test
    void searchUsesDefaultsAndReturnsPage() throws Exception {
        Transaction tx = transaction();
        when(searchTransactions.search(TransactionFilter.none(), new PageQuery(0, 20)))
                .thenReturn(new PageResult<>(List.of(tx), 0, 20, 41));

        mockMvc.perform(get("/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(tx.getId().toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.totalItems").value(41))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void searchPassesFiltersAndConvertsPageToZeroBased() throws Exception {
        TransactionFilter filter = new TransactionFilter("acc-1", TransactionStatus.REJECTED, TransactionType.DEBIT);
        when(searchTransactions.search(filter, new PageQuery(2, 5)))
                .thenReturn(new PageResult<>(List.of(), 2, 5, 11));

        mockMvc.perform(get("/transactions")
                        .param("accountId", "acc-1").param("status", "REJECTED").param("type", "DEBIT")
                        .param("page", "3").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(3))
                .andExpect(jsonPath("$.limit").value(5))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"page, 0", "limit, 0", "limit, 101"})
    void outOfRangePagingReturns400(String param, String value) throws Exception {
        mockMvc.perform(get("/transactions").param(param, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value(param));

        verifyNoInteractions(searchTransactions);
    }

    @ParameterizedTest
    @CsvSource({"status, DONE", "type, TRANSFER", "page, abc"})
    void unknownParameterValueReturns400(String param, String value) throws Exception {
        mockMvc.perform(get("/transactions").param(param, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.detail").value("Invalid value '%s' for parameter '%s'".formatted(value, param)));
    }

    // ---- GET /transactions/{id} ----

    @Test
    void getByIdReturnsTransaction() throws Exception {
        Transaction tx = transaction();
        when(searchTransactions.getById(tx.getId())).thenReturn(tx);

        mockMvc.perform(get("/transactions/{id}", tx.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tx.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getByIdReturns404WhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(searchTransactions.getById(id)).thenThrow(new TransactionNotFoundException(id));

        mockMvc.perform(get("/transactions/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void getByIdWithInvalidUuidReturns400() throws Exception {
        mockMvc.perform(get("/transactions/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    private static Transaction transaction() {
        return Transaction.create("acc-123456", TransactionType.CREDIT, new BigDecimal("1500.00"), "MXN",
                "Transferencia recibida", NOW);
    }
}
