# Payment processing

The checkout business layer calls `PaymentCoreApi.authorize`, then calls `capture`
on the same payment only when authorization returns `SUCCESS`. Each operation has
its own transaction ID. Checkout does not create a third financial transaction.

`CheckoutService` now injects `PaymentRepository`. It validates the request, creates
one parent payment at `CREATED / PENDING`, and saves `AUTHORIZATION / PENDING`
before invoking the core API with that parent's ID. After authorization succeeds,
it saves `CAPTURE / PENDING` before capture. Both calls use the parent's payment
method. The final payment state is saved before the service returns its result.

A failed create or stage update stops the next operation. A failed outcome update
throws an API exception, so a success response is never returned without saving the
payment outcome. If an operation throws or returns an invalid transaction, the
service attempts to save `UNKNOWN` at that stage, then propagates the original
error. If that save also fails, the persistence error is attached as a suppressed
exception; the last saved state remains available for later investigation.

`CheckoutService` uses exhaustive switches to implement this policy:

| Transaction outcome | Authorization decision | Capture decision |
| --- | --- | --- |
| `SUCCESS` | Run capture | Return `SUCCEEDED` |
| `FAILURE` | Return `FAILED`; no capture | Return `FAILED` |
| `UNKNOWN` | Return `UNKNOWN`; no capture | Return `UNKNOWN` |

The result uses the shared `PaymentState` and `PaymentStage` enums; there are no
separate checkout status/stage enums. It includes the payment ID, state, last attempted stage,
authorization transaction ID, and capture transaction ID (null if not attempted).
When capture fails, the authorization remains successful. Pending and unknown
results remaining unresolved after the adapter retry policy end the synchronous request
without starting background work. Provider PENDING never triggers immediate resubmission.
Gateway exceptions and provider `PENDING` responses map to transaction `UNKNOWN`.
The provider's original status is retained in `gatewayStatus`. Persistence failures
propagate as API errors. Payment state still includes `PENDING` while execution is
in progress; it is separate from the three-value transaction status.

## Run locally

Use Java 21 and Maven from the `PaymentProcessing` directory:

```sh
mvn spring-boot:run
```

The application listens on `http://127.0.0.1:8080`. The default `mock` profile
includes `mock-ledger`: authorization runs in-process and capture uses a local
REST mock. PostgreSQL, Kafka, and external service accounts are not required.
Payments and transactions are saved to `data/payments.json` (ignored by Git).
Stop with Ctrl-C; restarting with the same file restores saved records.

Submit a checkout in another terminal:

```sh
curl -sS http://127.0.0.1:8080/api/payments/checkout \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 33333333-3333-4333-8333-333333333333' \
  -d '{"accountId":"11111111-1111-1111-1111-111111111111","paymentMethodId":"22222222-2222-2222-2222-222222222222","amount":12.34,"currency":"USD"}'
```

The default response has `status: "SUCCEEDED"`, `stage: "CAPTURE"`, a payment ID,
and separate authorization/capture transaction IDs. Copy its payment ID:

```sh
curl -sS http://127.0.0.1:8080/api/payments/REPLACE_WITH_PAYMENT_ID
```

The read response includes parent identity, amount/currency, `status`, `stage`,
and a `transactions` array with each transaction's ID, type, status, lifecycle
state, provider status, and timestamps. Missing payments return 404; malformed
UUIDs return 400. Reads during execution may show intermediate progress; parent
and transaction reads are separate, not a combined atomic snapshot.

To select mock outcomes, stop the app and restart with overrides:

```sh
mvn spring-boot:run -Dspring-boot.run.arguments="--payment.authorization.mock.outcome=SUCCESS --payment.ledger.mock.outcome=PENDING"
```

Both outcome settings support `SUCCESS`, `FAILURE`, `PENDING`, and `UNKNOWN`.
Authorization failure/pending/unknown stops before capture. Ledger pending/unknown
returns payment `UNKNOWN` at stage `CAPTURE`; it does not automatically resume.
Use `--server.port=8081` or `--payment.store.path=/path/to/payments.json` in the
same arguments to override the port or storage path. Do not run two instances
against the same file. Repeating a POST with the same key and matching details
returns the saved payment state without re-executing operations. A new key creates
a new payment, even for identical details. Use GET to inspect a known payment.

This local demo accepts any valid account and payment-method UUIDs, including the
example values; no account/method catalog or seeded balances are needed. It has
no authentication and binds to loopback by default. Authorization mock `UNKNOWN`
simulates a plugin exception rather than making a real network call.

An executable JAR is also available:

```sh
mvn package
java -jar target/PaymentProcessing-1.0-SNAPSHOT.jar
```

## Transaction lifecycle

Transactions now persist both `state` and `status`:

| Authorization milestone | Lifecycle state | Transaction status |
| --- | --- | --- |
| Intent saved | `AUTHORIZATION_REQUESTED` | `UNKNOWN` |
| Provider confirms approval | `AUTHORIZATION_COMPLETED` | `SUCCESS` |
| Provider confirms decline | `AUTHORIZATION_COMPLETED` | `FAILURE` |
| Timeout or provider still processing | `AUTHORIZATION_REQUESTED` | `UNKNOWN` |

`TransactionState` also supplies corresponding `CAPTURE_REQUESTED/COMPLETED` and
`CHECKOUT_REQUESTED/COMPLETED` values for the existing operation types. The leaving
callback inserts the requested intent; the entering callback saves the lifecycle
state and status together. The operation callback retains the provider status in
the context so the entering callback can persist it. A pending response is stored
as `gatewayStatus = PENDING`, with status `UNKNOWN` and the requested state.

Completed means a definitive outcome is known, including a decline. Requested
records durable intent, not proof of network delivery. `processedAt` records the
last outcome-write time and can be present even when the outcome is unresolved.
Authorization and capture execute these callbacks through concrete runners and
the file-backed transaction adapter. Capture saves `CAPTURE_REQUESTED / UNKNOWN`
before posting to the ledger. Confirmed success or failure becomes
`CAPTURE_COMPLETED`; unresolved outcomes keep `CAPTURE_REQUESTED / UNKNOWN`.

## HTTP contract

`CheckoutController` requires exactly one `Idempotency-Key` header on
`POST /api/payments/checkout`. Its value is case-sensitive and must contain 1–64
ASCII letters, digits, hyphens, or underscores. A client-generated UUID is suitable.
Missing, empty, malformed, or multiple values return HTTP 400 before payment creation.
Header names are case-insensitive. The key is preserved in `CheckoutCallContext`,
separately from the fresh tracing request ID, and passed through the workflow.

The key is stored as an immutable field on the parent payment. Keys are globally
unique, case-sensitive, and retained without automatic expiry in this local store;
`accountId` identifies the customer and is not part of the uniqueness constraint.
The client uses one key per logical checkout and reuses it for retries. A new
intentional checkout uses a new key.

`PaymentRepository.createOrFind` registers the key and payment atomically. The file
implementation synchronizes lookup and snapshot replacement; only the request
that creates the record executes authorization/capture. Matching duplicates return
HTTP 200 with the existing payment ID, current state/stage, and saved transaction
IDs. This includes `PENDING`, `FAILED`, and `UNKNOWN`, without resuming the workflow.
IDs may be null before their transaction intents exist. Payment and transaction
values for this duplicate response are read under the same repository lock.

Conflicting details return HTTP 409 with code
`IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST`. The error does not include the
original payment ID or details. It leaves the existing payment unchanged and
executes no new financial operation; it does not mark the original payment failed.

The request-comparison rule is implemented in `CheckoutRequestIdentity`: account
ID, payment-method ID, amount, and currency must all match. Amounts compare by exact
numeric value (`10.0` equals `10.00`); there is no rounding. Payment progress,
generated IDs, and tracing metadata are excluded. The identity is compared only
within an existing key mapping; equal payloads with different keys do not by
themselves identify a duplicate. The service enforces this comparison before
returning an existing keyed payment.

The JSON body is unchanged:

```json
{
  "accountId": "11111111-1111-1111-1111-111111111111",
  "paymentMethodId": "22222222-2222-2222-2222-222222222222",
  "amount": 12.34,
  "currency": "USD"
}
```

Valid execution results return HTTP 200 with the business status in the body.
HTTP 200 alone does not mean payment success. This includes `PENDING` and
`UNKNOWN`; GET exposes saved progress; there is no automatic follow-up execution. Invalid input
returns 400. Execution/persistence exceptions return 500 with a generic problem
response; this is not a confirmed payment decline and must not prompt a blind retry.

## Current integration boundary

The callable Java path is:

```text
CheckoutController → CheckoutService → PaymentCoreApi → PaymentAutomatonRunner
```

`AuthorizationAutomatonRunner` implements `PaymentAutomatonRunner` for `AUTHORIZE`.
It validates the request against its saved parent payment (account, method, amount,
currency, and eligibility), then delegates to `PaymentWorkflowRunner` with a fresh
`AuthorizationStateMachineHelper` for that invocation. The executor calls
`AuthorizeInitiated`, `AuthorizeOperation`, and `AuthorizeCompleted` in order.

`FilePaymentDAO` adapts the transaction repository to the existing DAO helper.
The file path uses the repository's atomic snapshot replacement as its persistence
boundary; it does not open a JDBC transaction or database connection. The JDBC
helper continues to use `REQUIRES_NEW`. Each intent save must return successfully
before the gateway callback runs. A failed outcome write propagates an error and
leaves the saved intent unresolved. Unexpected gateway exceptions or invalid
responses trigger a best-effort UNKNOWN outcome write before the original error
is propagated, with any secondary persistence failure suppressed.

`AuthorizationRunnerConfiguration` supplies `DefaultPaymentAutomatonRunner` when a
`LedgerClient` bean is available and no custom runner is registered. It dispatches
authorization and capture to their respective runners. Without a ledger client,
the configuration supplies the authorization-only runner. Supply a `PaymentPluginAPI` bean for the mocked gateway. The local
adapter uses one configured provider (`payment.plugin.name`, default `mock`);
provider selection and deleted-method handling from a payment-method catalog are
not implemented. No real gateway is contacted by the tests.

The authorization runner requires an existing parent payment. Use the core API
overload accepting `paymentId`, as checkout does. The older authorization overload
that generates an ID does not create a parent record and therefore cannot be used
with this runner without further standalone-payment integration.

`CaptureAutomatonRunner` requires a successful saved authorization for the same
account, amount, and currency. It resolves the payment method from the parent,
rejects an existing capture attempt, and executes `CaptureInitiated`,
`CaptureOperation`, and `CaptureCompleted`. Capture calls the ledger REST client;
it does not invoke the card-provider capture method. A successful ledger update
completes capture and checkout without a separate ledger-posting phase.

The existing-attempt check prevents sequential duplicate captures, but is not an
atomic concurrent-request guard for independent capture callers. Checkout now
has atomic key registration. Downstream calls now use transaction IDs as keys and
the mocks deduplicate them. Durable downstream deduplication, distributed locking,
and resumption remain future work. Bounded immediate adapter retries are implemented. Direct financial checkout operations are rejected
by the dispatcher; sequencing belongs to `CheckoutService`.

`PaymentApplication` wires the complete HTTP checkout and read flow. Its default
mock profile supplies `MockAuthorizationPlugin` and the local REST ledger.
Database/JPA and Kafka auto-configuration are excluded from this file-backed demo.
The controller accepts account IDs directly; it does not resolve an authenticated account.

## Downstream operation identity

Each saved authorization or capture transaction UUID is also its downstream
idempotency key. No additional UUID is generated for an HTTP attempt. The checkout
key identifies the parent request; it is not reused for authorization or capture.
The leaving callback saves the transaction first. Operation callbacks then validate
that their context matches that transaction before sending its ID downstream.

`PaymentPluginAPI.authorizePayment` defines its `transactionId` parameter as the
provider idempotency key. A real adapter must forward this value to its provider's
idempotency mechanism. The current authorization mock is in-process and atomically
records requests by transaction ID. Repeated calls return the configured outcome
without another hold; changed financial parameters or plugin properties raise a
plugin exception. A fresh tracing context does not change the operation identity.

`RestLedgerClient` sends `Idempotency-Key: <capture transaction UUID>` in addition
to the body transaction ID. The REST mock requires the two to match. It atomically
records the operation and simulated posting, returns the original receipt for
matching repeats, and returns HTTP 409 for conflicting parameters. The client
keeps such conflicts unresolved instead of translating them into a financial decline.
A timed-out first response can be recovered by explicitly resending the same request;
the mock delays the first response only and replays completed responses immediately.

Both downstream mocks keep their deduplication records in memory for their lifetime.
Reopening the payment file preserves transaction keys, but restarting a downstream
mock loses its deduplication records and mock holds/entries. Tests demonstrate
payment-store reopen against still-running mocks, not durable remote-service recovery.
A shared immediate retry executor is implemented; no janitor or workflow-resume API exists yet.
Future recovery must load and reuse the original transaction, not rerun its leaving
callback to create a fresh transaction ID. Real downstream adapters must enforce
idempotency within their documented retention and account/region scope.

## Shared downstream retry policies

Spring-managed adapters opt in on a public, non-final method:

```java
@RetryableOperation(policy = "authorization", classifier = AuthorizationRetryClassifier.class)
```

`RetryExecutor` is the shared execution contract; `DefaultRetryExecutor` implements
attempt limits, exponential backoff, full jitter, deadlines, and interruption.
`RetryConfiguration` registers the executor, classifiers, and annotation processor.
Only the adapter invocation is repeated. Intent persistence, state transitions,
and outcome persistence stay outside this boundary. Every attempt keeps the
original saved transaction UUID and request arguments.

Configuration lives in `src/main/resources/application.properties`. Policies inherit
`payment.retry.defaults.*` and selectively override fields under
`payment.retry.policies.<name>.*`. Startup rejects invalid limits and annotations
referring to unknown policies or missing classifiers. `max-attempts` includes the
first call; setting it to 1 disables retries.

| Setting | Shared default | Authorization override | Capture ledger override |
| --- | --- | --- | --- |
| `max-attempts` | 3 | — | — |
| `attempt-timeout` | 1s | — | — |
| `max-elapsed-time` | 5s | 4s | 3s |
| `initial-delay` | 100ms | — | — |
| `multiplier` | 2 | — | — |
| `max-delay` | 1s | — | — |
| `jitter` | FULL | — | — |

The elapsed budget includes calls and sleeps and is per operation invocation,
not a whole-checkout deadline. Each attempt runs on a virtual thread; the caller
waits synchronously. Thread-local caller state is not propagated; dependencies
needed by the adapter must be passed explicitly. The REST timeout is clamped to
the remaining attempt budget. Executor timeouts cancel the attempt and stop retries,
since interruption cannot prove the remote operation stopped. Arbitrary code that
ignores interruption may continue running; no additional attempt is launched in
that invocation after its executor timeout.

`AuthorizationRetryClassifier` and `LedgerRetryClassifier` translate adapter results
and exceptions into facts used by `RetryDecision`. `classifyUnresolved` is called
for transient/unknown outcomes. It requires valid downstream idempotency protection;
explicit provider prohibitions veto retries. Transient failures may retry, while
other unknown outcomes additionally require explicit permission. Declines,
validation errors, conflicts, and pending results are not retried. `RECONCILE` means
stop immediate retries and retain the unresolved outcome; it does not enqueue work.

The ledger adapter classifies HTTP 429/502/503/504 and transport IO failures as
transient. Unclassified errors, including HTTP 500, stay unresolved. `Retry-After`
seconds or HTTP dates impose a minimum wait even when greater than the local backoff
cap. Guidance exceeding the remaining budget ends the invocation without another
call; malformed guidance is treated conservatively. The local ledger configuration
explicitly enables its known idempotency guarantee; the two-argument
`RestLedgerClient` constructor leaves this guarantee disabled. Real integrations
must validate their provider's scope and retention contract before enabling it.

Retries exhausted with an uncertain error persist `UNKNOWN`, retaining the
operation's `REQUESTED` lifecycle state. No capture follows unresolved authorization.
Pending provider responses are also unresolved, not permission to send again.
Application retry counters and debug scheduling logs are available on the executor;
retry budgets/counters are not persisted across invocations or process restarts.

For a local lost-response demonstration, set
`payment.authorization.mock.transient-failures=2` and
`payment.ledger.mock.transient-failures=2` with both mock outcomes `SUCCESS`.
Each mock applies one effect, reports two transient errors, then confirms success
on the third attempt. These failure counters are shared across calls to that mock
instance and reset on restart. The HTTP tests verify one hold, one ledger entry,
and exactly two saved transactions, including UNKNOWN results when retries run out.

Annotations require calls through the Spring bean proxy. Direct construction and
self-invocation bypass retries. A new adapter supplies its own classifier and named
policy; avoid another retry loop in its HTTP SDK. Durable background reconciliation,
service-wide retry budgets, concurrency limits, and production metrics integration
remain separate work.

## Mock ledger REST integration

Activate the Spring profile `mock-ledger` and provide a `PaymentPluginAPI` bean for
authorization. `MockLedgerConfiguration` starts a local ledger server and supplies
`RestLedgerClient`. The mock records requests and successful entries in memory;
it implements no balances or accounting. These mock entries disappear on restart,
while payment and transaction records remain in the JSON snapshot.

| Property | Default | Purpose |
| --- | --- | --- |
| `payment.ledger.mock.port` | `0` | Loopback port; zero selects an available port |
| `payment.ledger.mock.outcome` | `SUCCESS` | `SUCCESS`, `FAILURE`, `PENDING`, or `UNKNOWN` |
| `payment.ledger.timeout` | `PT2S` | REST request timeout as an ISO-8601 duration |

The client posts to `/ledger/entries` with `accountId`, `paymentId`, `transactionId`,
`amount`, and `currency`. Responses contain `status`, `entryId`, `code`, and
`message`. Confirmed success requires an entry ID, stored in the transaction's
existing `gatewayTransactionId` field. HTTP 202 means pending. Timeouts, connection
errors, server errors, and invalid responses remain unknown unless an eligible
adapter retry confirms the outcome. The workflow itself is never retried. Explicit ledger failure is a confirmed failed capture. A timeout can occur
after the ledger has applied its update, so it must not be treated as a decline.

## Local file-backed storage

`PaymentRepository` defines payment create/find/update operations (and `findAll`
for inspecting restored records). `FilePaymentRepository` implements it using
in-memory maps backed by a single JSON file. It also implements
`PaymentTransactionRepository` for storing the existing transaction records;
transaction lifecycle behavior is unchanged.

`PaymentRepositoryConfiguration` supplies a shared file repository for constructor
injection when no other `PaymentRepository` bean is provided. Set the Spring property
`payment.store.path` to choose the file; it defaults to `data/payments.json` relative
to the working directory. Spring closes the repository on shutdown. The existing
core API overload accepting an existing payment ID is used by checkout and by the
concrete authorization runner's tests.

```java
Payment payment = new Payment(UUID.randomUUID(), accountId, paymentMethodId,
        new BigDecimal("12.34"), Currency.getInstance("USD"));
try (FilePaymentRepository repository = new FilePaymentRepository(Path.of("data/payments.json"))) {
    repository.create(payment);
    payment.setPaymentStage(PaymentStage.AUTHORIZATION);
    repository.update(payment);
}
// A new repository opened with the same path restores the saved records.
```

The file has `version`, `payments`, and `transactions` fields. New snapshots use
version 3 and include payment `idempotencyKey` as well as transaction `state` and
`gatewayStatus`. Version 1 and 2 snapshots remain readable; older payments without
keys stay unkeyed. The key index is rebuilt on startup and duplicate keys in a
snapshot cause startup to fail. The next mutation writes version 3. Version 1
transaction statuses are migrated in memory:
`PAYMENT_FAILURE` becomes `FAILURE`, and `PENDING` becomes `UNKNOWN` with provider
status `PENDING`. Money remains decimal with its original precision and scale.

Fresh SQL tables use `db/payment-schema.sql`. For an existing PostgreSQL table,
`db/migrations/V2__transaction_lifecycle.sql` adds and backfills lifecycle/provider
status columns and replaces the old status constraint. This script is supplied
for one-time application to the original schema; no migration runner is configured
and no live database has been modified. Its transformations are tested using H2
in PostgreSQL mode, not a live PostgreSQL server.

Transactions must
reference a stored parent payment with matching account and currency. Create
rejects duplicate IDs; update rejects missing records and changes to identifying
data. Reads return copies of mutable payments, so edits take effect only on update.
For existing records, `updatePaymentAndTransaction` writes both changes together.

Every mutation writes and flushes a temporary snapshot in the same directory,
then atomically replaces the file before updating memory. An unsuccessful write
does not change the in-memory store. Unsupported atomic replacement fails rather
than falling back to an unsafe overwrite. Incomplete temporary files are ignored
on startup; corrupt or unsupported snapshots are reported instead of reset.

Writes are serialized, and an adjacent `.lock` file enforces one repository writer
per path. Use try-with-resources to release the lock; process termination also
releases the operating-system lock. Do not edit or remove store/lock files while
the repository is running. Stale read-modify-write conflicts are not versioned.

This is a small local mock that rewrites the full snapshot on each write. It tests
process restarts, not database-equivalent power-loss durability or multi-instance
operation. Use the same canonical file path when reopening. The repository does
not automatically retry or resume unknown payments. Checkout persists parent
payment progress here, and authorization and capture callbacks persist their
transactions through the same repository. Payment and transaction writes are not an atomic combined
unit in the checkout integration, so recovery must later reconcile incomplete progress.

Checkout deduplication is supported by the file repository's single-writer model.
A crash after saving the parent but before executing operations can leave
`CREATED / PENDING`; retries return that saved state without taking over execution.
Recovery of abandoned work is separate future work. Keys must be preserved when
restoring backups or moving stores. Legacy internal callers using a plain
`CallContext` remain unkeyed; callers needing deduplication must supply
`CheckoutCallContext`. The HTTP endpoint always requires a key.

## Verification

Using Java 21, run the test suite from this module:

```sh
mvn test
```

JUnit runs the checkout policy scenarios, Spring MVC request/response checks, and
existing API/callback/persistence contract checks. Checkout tests verify saved stages
before operations, the same parent identity, reload of all five transaction outcome paths,
and stopping on persistence errors. Focused controller tests use a fake runner. `PaymentApplicationTest` additionally
starts the actual HTTP application, sends checkout/read requests for seven outcome
combinations, and restarts the app to verify restored records without new network calls.
Persistence checks use in-memory H2 and temporary JSON files. File-store tests
include reloading after a separate JVM exits abruptly without closing its store.
Authorization integration tests execute the real runner and callbacks, mocking
only the gateway and injected storage failures. The mocked gateway reads the JSON
snapshot to verify the intent is durable before it is called. Tests also cover
all provider outcomes, invalid requests, failed intent/outcome persistence, and
Spring wiring to the real authorization runner.
Capture integration tests run the complete checkout service through both real
runners and a local HTTP mock ledger. They inspect the on-disk capture intent from
inside the REST handler, reopen the file to verify outcomes, and cover ledger
failure/pending/unknown, timeouts after ledger updates, and persistence failures.
The suite also checks invalid capture prerequisites, Spring profile wiring, and
malformed/uncertain HTTP responses. Local loopback socket access is required.
No external database or gateway is required.

Idempotency verification covers same-key repeats for all seven HTTP outcome
scenarios before and after restart, exact numeric amount comparison, conflicts on
each request field (including a different customer), and distinct-key payments.
Concurrent tests verify one registration winner and pending responses during both
authorization and capture without additional operations. Failed registration leaves
no in-memory payment/key and invokes no runner. Legacy snapshot loading and duplicate
key corruption detection are also tested. The current suite has 190 passing tests. Downstream checks cover concurrent
attempts, distinct operation IDs, payload conflicts, required ledger headers,
replayed outcomes, recovery after a lost ledger response, and reuse of saved keys
after reopening the payment store.
