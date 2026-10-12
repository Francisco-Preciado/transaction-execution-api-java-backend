# Transaction Execution API — Task List

Stack assumption: **Java 21 + Spring Boot 3**.

Suggested timeline (3 days): **Day 1** → Phases 0–3 · **Day 2** → Phases 4–7 · **Day 3** → Phases 8–9 + polish.

---

## Phase 0 — Setup & decisions
- [x] Run `git init` and add a `.gitignore` for Java, Maven/Gradle, and the IDE
- [x] Generate a Spring Boot 3 project (Web, Validation, Data JPA, Actuator, Lombok optional)
- [x] **Choose persistence:** PostgreSQL (fits high volume, matches Spin's stack); H2/Testcontainers for tests
#### *PostgreSQL selected for the project.*

- [x] **Choose the provider mock:** WireMock (docker-compose for local runs, embedded in tests)
#### *WireMock selected for api's and http requests and Mockito for unit tests with JUnit*

- [x] **Choose the resilience library:** Resilience4j (timeouts, retries, circuit breaker)
#### *Resilience4j for the library to use on the project

- [x] Define the package layout (hexagonal/clean): `domain`, `application`, `infrastructure` (persistence, provider client), `api` (REST)
#### *Hexagonal architecture would be the selected for the project*
#### *Layout: `domain` (model, ports, exceptions) · `application` (use cases) · `infrastructure/web` (REST, inbound) · `infrastructure/persistence` and `infrastructure/provider` (outbound). REST lives under `infrastructure/web` because the base package is already named `api`.*
- [x] Maven wrapper (`mvnw` / `mvnw.cmd`) so the build doesn't depend on a local Maven install
- [x] Dependencies in `pom.xml`: Data JPA, PostgreSQL, Flyway, Actuator, Resilience4j + AOP, WireMock, Testcontainers

## Phase 1 — Domain
- [x] `Transaction` entity/aggregate: `id`, `accountId`, `type`, `amount` (`BigDecimal`), `currency`, `description`, `status`, `providerTransactionId`, `balanceAfter`, `failureCode`, `failureMessage`, `createdAt`
- [x] `TransactionType` enum: `CREDIT`, `DEBIT`
- [x] `TransactionStatus` enum, e.g. `PENDING`, `EXECUTED`, `REJECTED`, `FAILED`
- [x] Outbound ports as interfaces: `TransactionWriteRepository` + `TransactionReadRepository` (split for CQRS-readiness), `TransactionProvider` (the domain doesn't depend on HTTP or the database)
- [x] Domain exceptions: `BusinessRuleViolationException`, `ProviderException`
#### *Pure Java domain under `domain/` (model, port, exception). Status changes only once: `PENDING` → `EXECUTED` | `REJECTED` | `FAILED`. Provider answers are a sealed `ProviderResult` (`Approved` / `Rejected`); timeouts and 5xx throw `ProviderException`.*

## Phase 2 — Business rules (run before the provider call)
- [x] Amount must be **> 1.00** (reject `<= 1.00`)
- [x] `DEBIT` must be **<= 10,000.00**; `CREDIT` has no limit
- [x] Currency must be `MXN`
- [x] One small validator class per rule (Strategy pattern), so a new rule doesn't change the others
- [x] Guarantee the provider is **never called** when a rule fails; return a descriptive error *(tested in `ExecuteTransactionUseCaseTest.ruleViolationNeverReachesProviderOrDatabase`)*
#### *`domain/rule`: `MinimumAmountRule` (`AMOUNT_TOO_LOW`), `DebitLimitRule` (`DEBIT_LIMIT_EXCEEDED`), `SupportedCurrencyRule` (`UNSUPPORTED_CURRENCY`), run by `TransactionRulesValidator` (fail-fast). Limits are constructor parameters with the challenge's values as defaults, so they can come from config later. Rules check a `TransactionRequest`, before any `Transaction` exists.*

## Phase 3 — Provider client (infrastructure)
- [x] Request/response DTOs for `POST /provider/v1/execute`, covering the success and error shapes
- [x] HTTP adapter (`RestClient`/`WebClient`) implementing the `TransactionProvider` port
- [x] Map HTTP 200 + `APPROVED` → `EXECUTED`, storing `transactionId` and `balance`
- [x] Map 4xx → `REJECTED`, keeping the provider's `code` and `message`
- [x] Map 5xx, timeouts, and connection errors → `FAILED` (or retry first)
- [x] Base URL and timeouts configurable in `application.yml`
- [x] *(Plus)* Resilience4j: connect/read timeouts, retries only on 5xx/timeouts (never on 4xx), circuit breaker
- [x] *(Plus)* Send an idempotency key (e.g. the internal transaction id) so retries don't execute a transaction twice
- [x] *(Plus)* Client-side idempotency: optional `Idempotency-Key` header on `POST /transactions` (unique column, replay → 200, in progress → 409, reused with a different body → 422)
#### *`infrastructure/provider`: `HttpTransactionProvider` (RestClient, HTTP/1.1) maps 200+APPROVED → `Approved`, 4xx → `Rejected` (except 408/429), 5xx/408/429/timeouts/I-O/malformed → `ProviderException`. `ResilientTransactionProvider` decorates it with `Retry(CircuitBreaker(call))` (3 attempts, exponential backoff; CB opens at 50% failures over 50 calls). Only the decorator is a Spring bean. Header `Idempotency-Key: <transaction id>`. Mapping `ProviderResult` → `Transaction` status happens in the Phase 4 use case.*

## Phase 4 — Application service
- [x] `ExecuteTransactionUseCase`: validate rules → persist `PENDING` → call the provider → update to the final status → return
- [ ] Document the trade-off: persisting before the call keeps a record if the provider call crashes *(explained in the class Javadoc; goes into the README in Phase 9)*
- [x] `SearchTransactionsUseCase` with filters and pagination
#### *`application/` is Spring-free; beans are wired in `infrastructure/config/ApplicationConfig`. No DB transaction spans the provider call (two short saves). Rule limits are configurable under `transaction.rules.*`. Added `PageQuery` (size capped at 100), `getById` + `TransactionNotFoundException`. The startup test mocks `TransactionRepository` until Phase 5.*

## Phase 5 — Persistence
- [x] JPA entity and repository adapter, kept separate from the domain model
- [x] Flyway/Liquibase migration for the `transactions` table
- [x] Indexes on `account_id`, `status`, `type`, `created_at`, plus a composite index on `(account_id, created_at)`
- [x] Use `numeric(19,2)` for money
- [x] Dynamic filtering with JPA Specifications or Querydsl
#### *`infrastructure/persistence`: `TransactionEntity` (with `@Version` for optimistic locking + `updated_at`), `JpaTransactionRepository` adapter, `TransactionSpecifications`. Flyway `V1__create_transactions_table.sql` with CHECK constraints. Indexes: `(account_id, created_at DESC, id DESC)`, `(status, created_at DESC, id DESC)`, `(created_at DESC, id DESC)`; no index on `type` alone (only 2 values, not selective). `TransactionRequest` now rejects amounts with more than 2 decimals so NUMERIC(19,2) never rounds silently. Tests run on real PostgreSQL via a shared Testcontainers base class.*

## Phase 6 — REST API
- [x] `POST /transactions`: request DTO with Bean Validation (`@NotBlank`, `@NotNull`, `@Positive`); returns **201 Created**
- [x] `GET /transactions?accountId=&status=&type=&page=&limit=`: paginated response (`items`, `page`, `limit`, `totalItems`, `totalPages`)
- [x] Cap `limit` at a maximum (e.g. 100) to protect the service under high volume
- [x] *(Optional)* `GET /transactions/{id}`
- [x] `@RestControllerAdvice` with consistent errors (RFC 7807 `ProblemDetail`):
  - 400 for invalid input
  - 422 for a business rule violation
  - ~~502/503 when the provider is unavailable~~ → recorded as `FAILED` and returned with 201 (see below)
- [x] Decide what `POST` returns when the provider rejects (201 with `status: REJECTED` vs 422) and document the choice
#### *Decision: **201 whenever a transaction was recorded**, outcome in `status` (`EXECUTED` / `REJECTED` / `FAILED`), with a `Location` header. 422 = business rule broken (nothing recorded, provider not called). 400 = invalid shape (`VALIDATION_ERROR` with per-field `errors`, `MALFORMED_REQUEST`, `INVALID_PARAMETER`). 404 = `TRANSACTION_NOT_FOUND`. 500 = `INTERNAL_ERROR` without internals. Every error body has a `code`. `page` is 1-based in the API. Amount: `@Digits(fraction = 2)`; the minimum amount stays a business rule (422), not `@Positive`.*

## Phase 7 — Tests
- [x] Unit tests per business rule, including boundary values: `1.00`, `1.01`, `10000.00`, `10000.01`, and a currency other than MXN
- [x] Use case unit tests with a mocked provider and repository (Mockito), including a check that the provider isn't called when validation fails
- [x] Provider adapter tests with WireMock: 200, 4xx, 5xx, timeout, retry/circuit breaker
- [x] Controller tests (`@WebMvcTest`) for validation and error mapping
- [x] *(Plus)* End-to-end integration test with Testcontainers PostgreSQL + WireMock
- [x] *(Plus)* JaCoCo coverage report
#### *146 tests, 98.4% line / 90.5% branch coverage. `TransactionApiEndToEndTest`: full context + PostgreSQL + WireMock over HTTP (approved, rejected, 503→retry→approved with the same `Idempotency-Key`, provider down → FAILED after 3 attempts, rule violation → nothing stored, search/pagination). Report: `target/site/jacoco/index.html`. Mockito loaded as a Java agent (no self-attach warning).*

## Phase 8 — Running & tooling
- [x] Multi-stage `Dockerfile`
- [x] `docker-compose.yml` with the app, PostgreSQL, and WireMock (stubs preloaded)
- [x] WireMock stub mappings for an approved transaction, `INSUFFICIENT_FUNDS`, and a 500 error, selectable by `accountId` for easy demos
- [x] OpenAPI / Swagger UI (springdoc)
- [x] Actuator health checks, including the circuit breaker state
- [x] *(Optional)* Postman collection or `.http` file with sample requests
#### *`docker compose up --build` → app :8080, PostgreSQL :5432, WireMock :8089. Dockerfile: JDK build stage + JRE-alpine runtime, layered jar, non-root, HEALTHCHECK. Demo accounts: `acc-no-funds` (422), `acc-blocked` (403), `acc-provider-error` (500), `acc-slow` (5s > 3s timeout); anything else approved. Verified live: 9 scenarios, provider called exactly 9 times (1+1+1+3+3, none for rule violations). Swagger at `/swagger-ui.html`. Open circuit shows in health but doesn't mark the app DOWN. `requests.http` has 13 sample requests.*

## Phase 9 — Documentation & delivery
- [x] `README.md`:
  - [x] How to run (docker-compose and locally)
  - [x] How to run the tests
  - [x] Sample requests (curl)
  - [x] Architecture overview and package structure
  - [x] **Persistence choice and why**
  - [x] Provider handling: status mapping, resilience, idempotency
  - [x] Scalability notes: indexes, stateless service, pagination, future work (Kafka/outbox events, partitioning by date)
  - [x] Assumptions and trade-offs
  - [x] **How AI was used**
- [ ] Review the code so every line can be explained in the next interview
- [ ] Clean, incremental commits; push to GitHub and share access *(commits done locally; push pending: no remote yet)*
- [x] Persistence choice and business-rule error messages double-checked against the running stack *(found and fixed: unquoted YAML limits lost their scale, "10000.0" instead of "10000.00")*
