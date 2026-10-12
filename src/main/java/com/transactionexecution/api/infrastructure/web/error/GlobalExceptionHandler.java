package com.transactionexecution.api.infrastructure.web.error;

import com.transactionexecution.api.domain.exception.BusinessRuleViolationException;
import com.transactionexecution.api.domain.exception.DomainException;
import com.transactionexecution.api.domain.exception.IdempotencyKeyReusedException;
import com.transactionexecution.api.domain.exception.InvalidPageQueryException;
import com.transactionexecution.api.domain.exception.RequestInProgressException;
import com.transactionexecution.api.domain.exception.TransactionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Central error handling. All errors are returned as RFC 9457 problem details with a stable,
 * machine-readable {@code code} property:
 * <ul>
 *   <li>400: malformed or invalid input ({@code VALIDATION_ERROR}, {@code MALFORMED_REQUEST}, ...)</li>
 *   <li>404: unknown transaction ({@code TRANSACTION_NOT_FOUND})</li>
 *   <li>409: first request with the same idempotency key still running ({@code REQUEST_IN_PROGRESS})</li>
 *   <li>422: business rule violation ({@code AMOUNT_TOO_LOW}, {@code DEBIT_LIMIT_EXCEEDED}, ...) or
 *       idempotency key reused for a different request ({@code IDEMPOTENCY_KEY_REUSED})</li>
 *   <li>500: anything unexpected ({@code INTERNAL_ERROR}), details only in the logs</li>
 * </ul>
 * Other framework exceptions (405, 415, ...) are handled by {@link ResponseEntityExceptionHandler}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String CODE = "code";
    static final String ERRORS = "errors";
    static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    static final String INVALID_PARAMETER = "INVALID_PARAMETER";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** One invalid field or query parameter. */
    record InvalidField(String field, String message) {
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleViolationException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violation", ex);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused", ex);
    }

    @ExceptionHandler(RequestInProgressException.class)
    public ProblemDetail handleRequestInProgress(RequestInProgressException ex) {
        return problem(HttpStatus.CONFLICT, "Request in progress", ex);
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ProblemDetail handleNotFound(TransactionNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Transaction not found", ex);
    }

    @ExceptionHandler(InvalidPageQueryException.class)
    public ProblemDetail handleInvalidPageQuery(InvalidPageQueryException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid pagination", ex);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setProperty(CODE, INTERNAL_ERROR);
        return problem;
    }

    /** Invalid request body fields (Bean Validation on {@code @Valid @RequestBody}). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<InvalidField> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new InvalidField(error.getField(), error.getDefaultMessage()))
                .toList();
        return validationProblem(ex, ex.getBody(), errors, headers, status, request);
    }

    /**
     * Invalid query, path or header parameters (e.g. {@code limit=500}). When a handler has
     * constraints on its parameters, an invalid {@code @Valid @RequestBody} also ends up here,
     * so its errors are reported per body field, as in {@link #handleMethodArgumentNotValid}.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<InvalidField> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result instanceof ParameterErrors bodyErrors
                        ? bodyErrors.getFieldErrors().stream()
                                .map(error -> new InvalidField(error.getField(), error.getDefaultMessage()))
                        : result.getResolvableErrors().stream()
                                .map(error -> new InvalidField(result.getMethodParameter().getParameterName(),
                                        error.getDefaultMessage())))
                .toList();
        return validationProblem(ex, ex.getBody(), errors, headers, status, request);
    }

    /** Unparseable JSON or a value of the wrong type, e.g. {@code "type": "TRANSFER"}. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                "Malformed JSON or a field has an invalid value (type must be CREDIT or DEBIT)");
        problem.setProperty(CODE, MALFORMED_REQUEST);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Query or path parameter that cannot be converted, e.g. {@code status=DONE} or a non-UUID id. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                "Invalid value '%s' for parameter '%s'".formatted(ex.getValue(), ex.getPropertyName()));
        problem.setProperty(CODE, INVALID_PARAMETER);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    private ResponseEntity<Object> validationProblem(Exception ex, ProblemDetail problem, List<InvalidField> errors,
                                                     HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        problem.setDetail("Request validation failed");
        problem.setProperty(CODE, VALIDATION_ERROR);
        problem.setProperty(ERRORS, errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, DomainException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(title);
        problem.setProperty(CODE, ex.getCode());
        return problem;
    }
}
