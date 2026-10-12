package com.transactionexecution.api.infrastructure.web.transaction;

import com.transactionexecution.api.application.ExecuteTransactionUseCase;
import com.transactionexecution.api.application.ExecuteTransactionUseCase.Execution;
import com.transactionexecution.api.application.SearchTransactionsUseCase;
import com.transactionexecution.api.domain.model.PageQuery;
import com.transactionexecution.api.domain.model.Transaction;
import com.transactionexecution.api.domain.model.TransactionFilter;
import com.transactionexecution.api.domain.model.TransactionStatus;
import com.transactionexecution.api.domain.model.TransactionType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@Tag(name = "Transactions")
@RestController
@RequestMapping("/transactions")
public class TransactionController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";
    /** Printable ASCII without spaces, matching the {@code idempotency_key VARCHAR(64)} column. */
    private static final String IDEMPOTENCY_KEY_PATTERN = "[\\x21-\\x7E]{1,64}";

    private final ExecuteTransactionUseCase executeTransaction;
    private final SearchTransactionsUseCase searchTransactions;

    public TransactionController(ExecuteTransactionUseCase executeTransaction,
                                 SearchTransactionsUseCase searchTransactions) {
        this.executeTransaction = executeTransaction;
        this.searchTransactions = searchTransactions;
    }

    /**
     * Executes a transaction. Returns 201 whenever a transaction was recorded; its {@code status}
     * tells the outcome (EXECUTED, REJECTED by the provider, or FAILED when the provider gave no
     * answer). Business rule violations return 422 and record nothing.
     *
     * <p>With an {@code Idempotency-Key}, repeating the request returns 200 with the transaction
     * stored the first time (and {@code Idempotent-Replayed: true}) instead of executing it again.
     */
    @Operation(summary = "Execute a transaction", responses = {
            @ApiResponse(responseCode = "201", description = "Recorded; see `status` for the outcome"),
            @ApiResponse(responseCode = "200", description = "Repeated `Idempotency-Key`: the transaction "
                    + "recorded by the first request, not executed again"),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The first request with this "
                    + "`Idempotency-Key` is still in progress; retry later",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Business rule violated (nothing recorded), "
                    + "or `Idempotency-Key` reused for a different request",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping
    public ResponseEntity<TransactionResponse> execute(
            @Parameter(description = "Client-generated key (e.g. a UUID) identifying this request. "
                    + "Retrying with the same key never executes the transaction twice. "
                    + "1 to 64 printable ASCII characters.")
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false)
            @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN, message = "must be 1 to 64 printable ASCII characters")
            String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request) {
        Execution execution = executeTransaction.execute(request.toDomain(), idempotencyKey);
        Transaction transaction = execution.transaction();
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(transaction.getId()).toUri();
        TransactionResponse body = TransactionResponse.from(transaction);
        if (execution.replayed()) {
            return ResponseEntity.ok().location(location).header(IDEMPOTENT_REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.created(location).body(body);
    }

    /** Lists transactions, newest first. {@code page} is 1-based; {@code limit} is capped at 100. */
    @Operation(summary = "Search transactions",
            description = "Newest first. `page` is 1-based; `limit` defaults to 20, max 100.")
    @GetMapping
    public PageResponse<TransactionResponse> search(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int limit) {
        TransactionFilter filter = new TransactionFilter(accountId, status, type);
        return PageResponse.from(searchTransactions.search(filter, new PageQuery(page - 1, limit)),
                TransactionResponse::from);
    }

    @Operation(summary = "Get a transaction by id", responses = {
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404", description = "Not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    @GetMapping("/{id}")
    public TransactionResponse getById(@PathVariable UUID id) {
        return TransactionResponse.from(searchTransactions.getById(id));
    }
}
