# Transaction Execution API

REST API that executes financial transactions (`CREDIT` / `DEBIT`) against an external provider,
stores the outcome and exposes it for querying.

**Stack:** Java 21 · Spring Boot 3.5 · PostgreSQL 17 · Flyway · Resilience4j · WireMock · Testcontainers · Docker

---

## Contents

- [Quick start](#quick-start)
- [Running locally and tests](#running-locally-and-tests)
- [API](#api)
- [Architecture](#architecture)
- [Design decisions](#design-decisions)
- [Scalability and performance](#scalability-and-performance)
- [Assumptions and trade-offs](#assumptions-and-trade-offs)
- [Testing](#testing)
- [Use of AI](#use-of-ai)

---

## Quick start

Requirements: **Docker** (with Compose).

```bash
docker compose up --build
```

This starts three containers:

| Service | URL | Purpose |
|---|---|---|
| `app` | http://localhost:8080 | The API |
| `postgres` | localhost:5432 | Database (schema created by Flyway on startup) |
| `provider-mock` | http://localhost:8089 | WireMock playing the external provider |

Then try it:

- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **Sample requests:** [`requests.http`](requests.http) (VS Code REST Client / IntelliJ HTTP Client)
- **Health:** http://localhost:8080/actuator/health (includes the provider circuit breaker state)

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H "Content-Type: application/json" \
  -d '{"accountId":"acc-123456","type":"CREDIT","amount":1500.00,"currency":"MXN","description":"Transferencia recibida"}'

curl "http://localhost:8080/transactions?accountId=acc-123456&page=1&limit=10"
```

### Demo accounts

The provider mock picks its answer from the `accountId`
([`wiremock/mappings`](wiremock/mappings)):

| `accountId` | Provider answers | Stored as |
|---|---|---|
| *any other* | 200 `APPROVED`, balance 5500.00 | `EXECUTED` |
| `acc-no-funds` | 422 `INSUFFICIENT_FUNDS` | `REJECTED` |
| `acc-blocked` | 403 `ACCOUNT_BLOCKED` | `REJECTED` |
| `acc-provider-error` | 500 | `FAILED` (after 3 attempts) |
| `acc-slow` | 200 after 5 s, past the 3 s timeout | `FAILED` (after 3 attempts, ~10 s) |

Stop with `docker compose down` (`-v` also deletes the database volume).

---

## Running locally and tests

Requirements: **JDK 21** and **Docker**. Maven is not needed; the project ships the Maven wrapper.

```bash
# Dependencies only, then the app from source
docker compose up postgres provider-mock
./mvnw spring-boot:run            # Windows: .\mvnw.cmd spring-boot:run

# All tests (unit, WireMock, PostgreSQL via Testcontainers, end-to-end)
./mvnw test
# Coverage report: target/site/jacoco/index.html
```

`JAVA_HOME` must point to a JDK 21. The database and end-to-end tests start their own PostgreSQL
container, so Docker must be running.

### Configuration

All settings have local defaults ([`application.yml`](src/main/resources/application.yml)) and can be
overridden with environment variables:

| Variable | Default | |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/transactions` | |
| `DB_USERNAME` / `DB_PASSWORD` | `transactions` / `transactions` | |
| `DB_POOL_SIZE` | `20` | Hikari pool size |
| `PROVIDER_BASE_URL` | `http://localhost:8089` | |
| `PROVIDER_CONNECT_TIMEOUT` / `PROVIDER_READ_TIMEOUT` | `2s` / `3s` | |

Business rule limits (`transaction.rules.*`), retries (`resilience4j.retry.instances.provider.*`) and
the circuit breaker (`resilience4j.circuitbreaker.instances.provider.*`) are also configured there.

---

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/transactions` | Execute a transaction |
| `GET` | `/transactions?accountId=&status=&type=&page=&limit=` | Search, newest first. `page` is 1-based (default 1); `limit` default 20, max 100 |
| `GET` | `/transactions/{id}` | Get one transaction |

### POST /transactions

```json
{
  "accountId": "acc-123456",
  "type": "CREDIT",
  "amount": 1500.00,
  "currency": "MXN",
  "description": "Transferencia recibida"
}
```

Response `201 Created` with a `Location: /transactions/{id}` header:

```json
{
  "id": "923d07b6-d67c-44ca-93a5-d27e13a7ba72",
  "accountId": "acc-123456",
  "type": "CREDIT",
  "amount": 1500.00,
  "currency": "MXN",
  "description": "Transferencia recibida",
  "status": "EXECUTED",
  "providerTransactionId": "txn-hcdw5lbh26io",
  "balanceAfter": 5500.00,
  "failureCode": null,
  "failureMessage": null,
  "createdAt": "2026-10-11T22:22:47.565572Z"
}
```

`failureCode` and `failureMessage` are filled when the status is `REJECTED` or `FAILED`.

#### Idempotency-Key (optional, recommended)

Send a client-generated `Idempotency-Key` header (e.g. a UUID, 1–64 printable ASCII characters) so
that retrying the same request never executes the transaction twice:

| Situation | Response |
|---|---|
| First request with the key | Executed normally → `201 Created` |
| Same key, same body, first request finished | `200 OK` with the **originally stored** transaction and `Idempotent-Replayed: true`. The provider is not called again |
| Same key, same body, first request still running | `409 Conflict`, `REQUEST_IN_PROGRESS`. Retry later with the same key |
| Same key, **different** body | `422`, `IDEMPOTENCY_KEY_REUSED`. Nothing is executed |
| No header | Every request executes (no de-duplication) |

A request rejected by a business rule (422) stores nothing, so retrying it with the same key runs
the rules again.

### Status codes

| Status | When | `code` in the body |
|---|---|---|
| **201** | A transaction was recorded. **Read `status` for the outcome:** `EXECUTED`, `REJECTED` (provider said no) or `FAILED` (provider gave no answer) | – |
| **200** | `POST` repeated with an `Idempotency-Key` already used: the original transaction is returned, nothing is executed | – |
| **400** | Invalid input: missing fields, more than 2 decimals, bad JSON, unknown enum value, `limit` > 100, malformed `Idempotency-Key` | `VALIDATION_ERROR` (with per-field `errors`), `MALFORMED_REQUEST`, `INVALID_PARAMETER` |
| **404** | Unknown id | `TRANSACTION_NOT_FOUND` |
| **409** | The first request with this `Idempotency-Key` is still being executed | `REQUEST_IN_PROGRESS` |
| **422** | A business rule is broken (nothing is stored and the provider is not called), or an `Idempotency-Key` is reused for a different request | `AMOUNT_TOO_LOW`, `DEBIT_LIMIT_EXCEEDED`, `UNSUPPORTED_CURRENCY`, `IDEMPOTENCY_KEY_REUSED` |
| **500** | Unexpected error (details only in the logs) | `INTERNAL_ERROR` |

Errors use [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details:

```json
{
  "type": "about:blank",
  "title": "Business rule violation",
  "status": 422,
  "detail": "DEBIT amount cannot exceed 10000.00 per transaction, got 10000.01",
  "instance": "/transactions",
  "code": "DEBIT_LIMIT_EXCEEDED"
}
```

### Business rules

Checked before anything is stored or sent to the provider:

1. Amount must be **greater than 1.00** (1.00 itself is rejected).
2. A `DEBIT` cannot exceed **10,000.00** per transaction (10,000.00 is allowed). `CREDIT` has no limit.
3. Only **MXN** is accepted.

---

## Architecture

Hexagonal (ports and adapters). The domain and application layers are plain Java with no Spring,
JPA or HTTP code; adapters implement the domain's ports.

```
                 ┌──────────────────────────────────────────────┐
  HTTP ────────► │ infrastructure/web     (REST controller, DTOs, error handler)
                 └──────────────┬───────────────────────────────┘
                                ▼
                 ┌──────────────────────────────────────────────┐
                 │ application            ExecuteTransactionUseCase
                 │                        SearchTransactionsUseCase
                 └──────────────┬───────────────────────────────┘
                                ▼
                 ┌──────────────────────────────────────────────┐
                 │ domain                 Transaction, rules, ports
                 │   port: TransactionWriteRepository   TransactionProvider
                 │         TransactionReadRepository
                 └───────────────┬──────────────────────┬───────┘
                   implemented by│                      │implemented by
                                 ▼                      ▼
        infrastructure/persistence            infrastructure/provider
        (JPA + Flyway, PostgreSQL)            (RestClient + Resilience4j) ───► Provider
```

```
src/main/java/com/transactionexecution/api
├── domain
│   ├── model          Transaction (aggregate), TransactionRequest, ProviderResult, PageQuery, ...
│   ├── rule           TransactionRule + MinimumAmount / DebitLimit / SupportedCurrency rules
│   ├── port           TransactionWriteRepository, TransactionReadRepository, TransactionProvider
│   └── exception      DomainException (with a stable code) and subclasses
├── application        ExecuteTransactionUseCase (command), SearchTransactionsUseCase (query)
└── infrastructure
    ├── web            TransactionController, request/response DTOs, GlobalExceptionHandler
    ├── persistence    TransactionEntity, JpaTransactionRepository, Specifications
    ├── provider       HttpTransactionProvider, ResilientTransactionProvider, DTOs
    └── config         Spring wiring, rule limits, OpenAPI
```

### Execution flow

```
POST /transactions
  1. Bean Validation on the body and Idempotency-Key          → 400 if invalid
  2. Idempotency-Key already stored?                           → 200 replay, 409 or 422; provider not called
  3. Business rules (amount, debit limit, currency)           → 422, nothing stored
  4. Save transaction as PENDING                               (short DB transaction; the unique key
                                                                 picks one winner among duplicates)
  5. Call the provider (timeouts + retries + circuit breaker)  (no DB connection held)
       Approved  → EXECUTED, store providerTransactionId and balanceAfter
       Rejected  → REJECTED, store the provider's code and message
       No answer → FAILED,   code PROVIDER_UNAVAILABLE
  6. Save the final status                                     (short DB transaction)
  7. 201 Created with the stored transaction
```

---

## Design decisions

### Persistence: PostgreSQL

- **Money and consistency.** `NUMERIC(19,2)` stores exact amounts, and ACID transactions with
  optimistic locking (`@Version`) keep each record consistent. That is what a ledger of financial
  transactions needs.
- **The queries are relational.** Filtering by account, status and type, newest first, with paging,
  maps directly to composite B-tree indexes.
- **Schema as code.** Flyway migrations version the schema, and CHECK constraints guard `type`,
  `status` and `amount > 0` at the database level too.
- **Fits the volume.** Millions of rows a day are well within a single PostgreSQL instance with the
  right indexes, and there are clear next steps for growth (see
  [Scalability](#scalability-and-performance)).
- *Alternatives considered:* in-memory/SQLite would not survive restarts or multiple instances.
  MongoDB would work, but the data is fixed-shape and relational, and exact decimal handling and
  multi-field indexes are more natural in PostgreSQL. It is also part of Spin's stack.

### Transaction lifecycle

A transaction is created as `PENDING` and moves **exactly once** to `EXECUTED`, `REJECTED` or
`FAILED`. The domain enforces this (`InvalidTransactionStateException`).

### Provider response mapping

| Provider answer | Result | Retried? |
|---|---|---|
| 200 + `APPROVED` | `EXECUTED` | – |
| 4xx (e.g. 422 `INSUFFICIENT_FUNDS`, 403) | `REJECTED` with the provider's code and message | No: it is a definitive "no" |
| 5xx, 408, 429, timeout, connection error, malformed 200 | `FAILED` (`PROVIDER_UNAVAILABLE`) | Yes |

**5xx counts as "no definitive answer", even when its body says `REJECTED`.** A server error
does not reliably tell you whether the debit was applied. 408 and 429 mean "try later", not "no".

### Resilience

`ResilientTransactionProvider` decorates the HTTP adapter with `Retry(CircuitBreaker(call))`:

- **Timeouts:** 2 s to connect, 3 s to read.
- **Retry:** 3 attempts with exponential backoff (200 ms, then 400 ms), **only** when there is no
  definitive answer. Rejections are never retried.
- **Circuit breaker:** opens when at least 50% of the last 50 calls failed (minimum 20 calls), then
  fails fast for 30 s. While it is open, transactions are stored as `FAILED` without calling the
  provider.
- **Idempotency:** every attempt sends `Idempotency-Key: <transaction id>`, so a retried `DEBIT`
  cannot be applied twice (see [Assumptions](#assumptions-and-trade-offs)).
- **Health:** the circuit state is visible in `/actuator/health`, but an open circuit does **not**
  mark the app `DOWN`, because restarting this service would not fix the provider.

Resilience4j is used programmatically instead of through annotations. The order of retry and
circuit breaker is explicit in the code, it is unit-testable without Spring, and no AOP is needed.

### Client idempotency (Idempotency-Key on POST /transactions)

The provider-side key above only protects **our** retries. Without a client key, a client that
retries `POST /transactions` (double click, its own timeout) would create a second transaction with
a new id, and the provider would apply it twice. So the API accepts its own `Idempotency-Key`:

- The key is stored with the transaction in `idempotency_key`, under a **unique constraint**
  (`V1__create_transactions_table.sql`). Rows without a key are `NULL` and never clash.
- Before executing, the use case looks the key up. If found, it **replays** the stored transaction
  (`200` + `Idempotent-Replayed: true`) without calling the provider again. It does not re-run the
  rules either, so a retry gets the original answer even if the limits changed in between.
- **Concurrent duplicates:** two identical requests can both miss the lookup. Only one `PENDING`
  insert succeeds; the other hits the unique constraint (translated to
  `DuplicateIdempotencyKeyException` by the repository) and is answered from the winner's row,
  which at that point is usually still `PENDING` → `409`. Checked with 5 parallel requests in the
  end-to-end tests: one `201`, one provider call, one row.
- **The body must match.** Reusing a key with a different account, type, amount, currency or
  description returns `422 IDEMPOTENCY_KEY_REUSED` instead of silently returning an unrelated
  transaction.
- The header is **optional** to keep existing clients working. A stricter API could require it on
  every `POST`.
- The provider still receives the internal transaction id as its key. A replay never reaches the
  provider, so one client key always maps to one provider key.

### Separate read and write ports (CQRS-ready, not full CQRS)

Commands and queries already go through different use cases. Their storage ports are split too:

- `TransactionWriteRepository` (`save`, `findByIdempotencyKey`) is used only by
  `ExecuteTransactionUseCase`. It must always hit the primary database: the idempotency check
  cannot tolerate stale data.
- `TransactionReadRepository` (`findById`, `search`) is used only by `SearchTransactionsUseCase`,
  which therefore cannot write.

Today one adapter, `JpaTransactionRepository`, implements both on the same table. The split lets
the read side move to a read replica or a dedicated read model later by adding a second adapter,
with no change to the domain, the use cases or the execution flow. A separate read model fed by
events (full CQRS) is not worth it yet: the queries are simple, and it would make reads
eventually consistent.

### Persist PENDING first, with no DB transaction around the provider call

- The `PENDING` row is written **before** calling the provider. If the process crashes mid-call, a
  record still exists and can be reconciled with the provider via the idempotency key. Without it,
  the provider could execute a transaction we never stored.
- The provider call is **not** inside a database transaction. With retries it can take seconds;
  holding a pooled connection that long would drain the pool under load. Instead there are two
  short writes.

### 201 for every recorded outcome; 400 vs 422

- `POST` returns **201** whenever a transaction was recorded, including `REJECTED` and `FAILED`.
  The resource exists and can be fetched by id, so 201 is accurate. The outcome is in `status`.
  *Alternative:* return 422 for `REJECTED`. That reads naturally for clients, but mixes "your
  request was invalid" with "the provider declined it".
- **400** is for the shape of the input; **422** is for business rules. That is why `amount` has
  `@Digits(fraction = 2)` but not `@Positive`: the minimum-amount rule owns the limits, so they
  live in one place.

### Business rules as strategies

Each rule is a small `TransactionRule` class with its own error code. `TransactionRulesValidator`
runs them in order and stops at the first violation. Adding a rule means adding a class, and the
limits come from configuration.

### Money precision

Amounts are `BigDecimal` everywhere and compared with `compareTo` (so `1.0` equals `1.00`). More
than 2 decimals is rejected with a 400; otherwise `NUMERIC(19,2)` would silently round `1.001`
to `1.00` after the rules had validated a different value.

---

## Scalability and performance

What is in place:

- **Stateless service:** instances scale horizontally behind a load balancer. State lives in
  PostgreSQL.
- **Indexes match the queries,** all sorted `created_at DESC, id DESC`:
  `(account_id, …)` for per-account queries (the main path), `(status, …)` for status filters and
  for finding stuck `PENDING` rows, and `(created_at, …)` for unfiltered listing. There is no index
  on `type` alone: with two values it is not selective enough to be used.
- **Stable paging:** `id` breaks ties between equal timestamps, so rows don't repeat or jump
  between pages. `limit` is capped at 100.
- **Short DB transactions** and a bounded Hikari pool. No connection is held during provider calls.
- **Fail fast:** the circuit breaker sheds load when the provider is down instead of piling up
  threads waiting on timeouts.

Next steps at higher volume (not implemented):

- **Cursor (keyset) pagination.** Offset paging plus `COUNT(*)` gets slower as tables grow. A
  cursor based on `(created_at, id)` stays constant-time.
- **Partition `transactions` by `created_at`** (monthly) to keep indexes small and archive cheaply.
- **Read replicas** for the query endpoints.
- **Reconciliation job** for rows left in `PENDING` or `FAILED`: re-query the provider with the
  idempotency key.
- **Transactional outbox + Kafka** to publish `TransactionExecuted` events for other services.
- **Asynchronous execution** (return `202 Accepted` and process from a queue) if provider latency
  becomes the bottleneck.
- **Virtual threads** (`spring.threads.virtual.enabled`) to cheapen blocking on the provider.

---

## Assumptions and trade-offs

- **The provider honors `Idempotency-Key`.** The contract does not mention it. Retrying a `DEBIT`
  after a timeout is only safe if the provider de-duplicates by that key. If it cannot, retries
  should be limited to connection errors (where the request never arrived).
- **Worst-case latency is about 10 s** (3 attempts × 3 s timeout + backoff). This is a tuning
  choice: a lower read timeout or fewer attempts shortens it at the cost of more `FAILED`.
- **`FAILED` means "outcome unknown",** not "definitely not executed". It needs reconciliation,
  which is described above but not implemented. Replaying a client `Idempotency-Key` returns the
  stored `FAILED` as-is; retrying with a **new** key risks a double execution if the provider did
  apply the first one.
- **A key stuck in `PENDING`** (process crashed mid-call) answers `409` until reconciliation
  resolves the row. Keys never expire; a TTL would be needed if the table is pruned.
- **Saving looks up the row first** (one primary-key read per save) so the JPA entity keeps its
  version. Separate `insert`/`updateOutcome` port methods would remove that read.
- **Currency is matched exactly:** `"mxn"` is rejected.
- **Out of scope:** authentication/authorization, rate limiting, multi-currency, and account or
  balance logic (owned by the provider, as the challenge states).

---

## Testing

`./mvnw test` runs **177 tests** (about 97% line and 91% branch coverage, JaCoCo):

| Level | What | Tools |
|---|---|---|
| Domain unit | Rules with boundary values (`1.00`, `1.01`, `10000.00`, `10000.01`, other currencies), state transitions, request validation, paging, request matching for idempotency | JUnit 5, AssertJ |
| Use case unit | Each outcome; `PENDING` saved before the provider call; **provider and DB never touched when a rule fails**; idempotent replay, key reuse, in-progress, lost insert race | Mockito |
| Provider adapter | Every status code, timeout, connection reset, malformed body, exact request body and idempotency header | WireMock |
| Resilience | Rejections not retried, transient failures retried, gives up after 3 attempts, circuit opens and fails fast | Resilience4j, Mockito |
| Persistence | Round trip, outcome update and versioning, every filter, ordering, paging, unique idempotency key, on the real schema | Testcontainers PostgreSQL |
| Web | Status codes, validation errors per field, error bodies, paging parameters, `Idempotency-Key` header (200 replay, 409, 422, invalid key) | `@WebMvcTest` |
| End-to-end | Full app + PostgreSQL + WireMock over HTTP: approved, rejected, 503 → retry → approved with the same idempotency key, provider down → `FAILED`, rule violation stores nothing, client retry with the same `Idempotency-Key` executes once, 5 concurrent duplicates execute once, search | Spring Boot Test |

---

## Use of AI

I used **Claude Code** (Anthropic's coding assistant, inside VS Code) as a pair programmer
throughout the challenge:

- **Planning:** turning the challenge statement into a phased task list ([`TASKS.md`](TASKS.md)),
  which I then worked through phase by phase.
- **Code and tests:** generating the implementation and tests for each phase, based on the
  decisions I made (PostgreSQL, WireMock, Resilience4j, hexagonal architecture). I reviewed each
  phase before moving on.
- **Environment and debugging:** setting up the Maven wrapper and Docker/WSL, and diagnosing
  issues such as Testcontainers 1.21.3 being incompatible with Docker Engine 29, the JDK HTTP
  client's HTTP/2 upgrade being reset by WireMock, and the IDE overwriting compiled classes during
  a JaCoCo run.
- **Documentation:** drafting this README.

Engineering decisions (stack, architecture, the 201/422 policy, how provider errors are treated)
were discussed and agreed explicitly, and I can explain every part of the code.
