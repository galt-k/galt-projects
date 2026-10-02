# Basic payment processing checklist

## Goal

Send a checkout request to a locally running application and observe one payment complete authorization and capture using mocked storage and external services.

Checkout succeeds only when both authorization and capture succeed. Both operations belong to the same payment and create separate transaction records.

The basic mock payment milestone is complete as of September 29, 2026. The runnable HTTP application and all 190 automated tests are verified. See README.md for startup and sample requests.

Checkout idempotency is now implemented: required header validation, global durable
key-to-payment mapping, atomic registration, and current-state responses to matching
retries. Changed account, payment method, amount, or currency returns HTTP 409 without
new operations. File snapshot v3 restores keys across restarts and accepts legacy
snapshots. Authorization and capture now use their saved transaction UUIDs as
separate downstream keys, with concurrent deduplication and conflict detection in
the mocks. Bounded immediate retries now share an executor, annotation, named configuration,
and adapter classifiers. Durable downstream deduplication and background recovery
remain separate future work.

## Scope for this milestone

- Use the latest simplified checkout flow: authorization followed by capture.
- Keep the business API, checkout orchestration, state transitions, and lifecycle callbacks real.
- Use in-memory reads backed by local JSON snapshots and configurable fake gateway responses. No PostgreSQL, Kafka, Redis, or external accounts are required to run the demo.
- Successful mocks complete immediately, allowing a final response in the same request. Pending or unknown results must be reported honestly; background resumption is a later milestone.
- Fraud evaluation, real integrations, distributed locks, durable downstream deduplication, automatic resumption after restart, and real ledger accounting are later work. Capture now posts to a local REST ledger mock. File-backed record restoration is implemented.

## Existing pieces to reuse

The project already contains the core API, operation callbacks, entering/leaving callbacks, mutable request context, plugin contracts, persistence contracts, and payment state classes. Complete and connect these pieces rather than rebuilding them.

Implemented pieces:

- The core API injects `PaymentAutomatonRunner.run(context)`. `DefaultPaymentAutomatonRunner` connects AUTHORIZE and CAPTURE to the existing workflow executor and file store when a ledger client is supplied.
- `AuthorizationStateMachineHelper` and `CaptureStateMachineHelper` supply concrete configuration, sharing ordered lifecycle execution in `OperationStateMachineHelper`.
- `CheckoutService` now creates the parent payment, saves stages before each core API call, and saves the overall outcome before responding. It uses the same parent ID and payment method for authorization and capture.
- The payment entity has UUID identity/account/method, decimal amount/currency, and mutable `PaymentState`/`PaymentStage`. Checkout saves parent progress through the file repository; authorization and capture callbacks now use `FilePaymentDAO` to save transactions to that store.
- The entering callback now refreshes the context after a committed update and preserves gateway exceptions as `UNKNOWN`.
- `PaymentApplication` starts the checkout and payment read endpoints with the mock profile and file repository. The old generated `Main` is not the application entry point.
- JUnit runs 190 checks, including complete HTTP checkout/read requests through real runners and a local REST ledger, with application restarts verifying restored records.

## Ordered tasks

### T1 — Align the API and runner contracts

- [x] Restore a compiling, consistent API-to-runner boundary.

Choose one public entry contract and wire its dependencies explicitly. Keep the existing user edits: use `CHECKOUT` for the business request and `AUTHORIZE` / `CAPTURE` for its underlying operations. Resolve the remaining `PURCHASE` naming expectations deliberately and update callers and tests. Keep business orchestration separate from execution of one transaction.

**Done when:** Java 21 main and test sources compile; no missing `run` overload or incompatible test constructor remains.

Implemented with constructor-injected `PaymentAutomatonRunner`. Checkout moved to `CheckoutService`; the core API exposes authorization and capture. `CHECKOUT` remains the business operation name; its financial transactions are `AUTHORIZE` and `CAPTURE`. The legacy `PURCHASE` enum value remains for compatibility but is not dispatched by the current runner or used by checkout. Concrete runner and application wiring are complete.

**Depends on:** None.

### T2 — Model payment-level workflow progress

- [x] Give each payment an identity, current state/stage, and associated transactions.

**Progress:** The payment model, shared enums, and file-backed repository are connected to checkout. Stages are `CREATED`, `AUTHORIZATION`, and `CAPTURE`; overall states are `PENDING`, `SUCCEEDED`, `FAILED`, and `UNKNOWN`. Transaction records reference their parent payment. Tests verify saved payment progress and outcomes after reopening the file. Real authorization and capture callbacks now persist their transaction records to the same store.

Transaction statuses are now `SUCCESS`, `FAILURE`, and `UNKNOWN`. The transaction
lifecycle is stored separately as operation-specific `REQUESTED` or `COMPLETED`
states. Provider `PENDING` remains unresolved (`UNKNOWN` / `REQUESTED`) with the
original provider status retained. Existing callbacks and JSON/SQL persistence
support these fields, including migration of previous status values.

- [x] Create the parent before authorization and save payment stage/state changes during checkout.

Use UUID identifiers. The workflow is checkout; a stored workflow-type field remains a later model decision. Define this successful path using separate stage/state values:

```text
CREATED / PENDING → AUTHORIZATION / PENDING → CAPTURE / PENDING → CAPTURE / SUCCEEDED
```

Define failure and unresolved outcomes as well. Record the authorization and capture transaction IDs separately. Capture success completes this MVP; it must not wait for the existing `LedgerPosted` event. Preserve a successful authorization when a later capture fails.

**Done when:** One payment can be inspected at every milestone, with its transaction outcomes independently visible. A timeout never becomes an assumed success or a confirmed decline.

**Depends on:** T1.

### T3 — Supply reusable mock adapters

- [x] Add file-backed storage and configurable gateway behavior for the mock application.

**Progress:** `FilePaymentRepository` now reloads JSON snapshots into in-memory maps, saves payments and existing transaction models, and supports combined outcome updates. Restart, abrupt process exit, concurrent writes, and failure cases are tested. A configurable local REST ledger mock and its Spring profile are implemented. The default mock profile now wires a configurable authorization plugin and runnable HTTP application. A payment-method catalog is deferred; the mock accepts valid UUIDs directly.

Store payments and transaction attempts. For this basic mock, account and payment-method UUIDs are supplied directly; no catalog or seeded balances are required. Support lookup by payment ID and verify account ownership. Provide gateway scenarios for success, decline, pending, and exception. Record calls so authorization/capture ordering and counts can be checked. Ensure the mock persistence path does not open real database connections.

**Done when:** Application wiring can use these adapters without infrastructure, and tests can inspect saved state and gateway calls. Saved records survive process restart when the same snapshot path is used; automatic workflow resumption remains later work.

**Depends on:** T2.

### T4 — Complete execution of one state-machine operation

- [x] Implement the concrete state-machine configuration and lifecycle execution.

- [x] Implement authorization using the actual leaving, operation, and entering callbacks with file-backed transaction writes.
- [x] Verify saved intent on disk before the mocked gateway call, zero gateway calls on intent failure, and no success on outcome persistence failure.
- [x] Add the equivalent concrete capture execution path through a REST ledger mock, with saved authorization validation and intent/outcome failure tests.

Authorization request validation checks the saved parent account, method, amount,
currency, and eligibility. Spring configuration supplies the real authorization
runner when no custom runner exists. Tests exercise the actual core API → runner →
callbacks → file store path for approval, decline, cancellation, pending, and errors.
Gateway calls remain mocked. With a ledger client, the dispatcher runs capture through a local REST call; the authorization-only executor still rejects capture explicitly.

Connect the existing `State` transition rules to the workflow execution contract. For each selected operation, execute the leaving callback, operation callback, result-driven transition, and entering callback in order. Reject invalid operations before external work. If intent persistence fails, do not call the gateway. Do not rerun the operation when entering a state. Keep standalone authorization and capture reusable.

**Done when:** Real application code executes an authorization or capture through all callbacks; tests mock only storage/network boundaries, not the runner or lifecycle sequence.

**Depends on:** T2, T3.

### T5 — Implement the checkout orchestration

- [x] Add a checkout workflow that authorizes first and captures only after authorization succeeds.

**Progress:** The service, exhaustive status switches, shared parent payment ID/method, separate transaction IDs, and failure/pending/unknown policies are implemented. Parent creation and payment progress persistence are connected to the repository. A failed prerequisite save stops the next operation; a failed final save prevents success acknowledgement. Integration tests now execute both concrete runners and verify two transactions, saved outcomes, and preservation of successful authorization when capture fails.

Create one payment, run an `AUTHORIZE` transaction, and then run a distinct `CAPTURE` transaction using the same payment ID and payment method. Update the payment-level state between operations. Invoke the reusable operation runner; do not call the public authorization API in a way that creates a second payment. Do not create a third financial `CHECKOUT` transaction or invoke the direct checkout plugin method for this staged flow.

**Done when:** The successful flow creates exactly two transactions, calls authorization once before capture once, and finishes the payment as `SUCCEEDED`. Failed, pending, or unknown authorization prevents capture. Failed or pending capture never produces checkout success.

**Depends on:** T4.

### T6 — Return the actual final outcome

- [x] Synchronize the context, stored transaction, payment state, and API response.

**Progress:** The entering callback replaces the immutable context transaction after a successful outcome commit, using the persisted timestamp. Gateway exceptions remain `UNKNOWN`; confirmed declines are returned to the business layer. `CheckoutResult` reports the payment state/stage just saved and both transaction IDs. Checkout passes its parent payment method to capture. Capture resolves a missing method from the saved parent. Full service/runner/store integration is verified against the REST ledger mock.

Refresh or replace immutable transaction objects after outcome persistence so the returned object does not remain `UNKNOWN`. Preserve gateway response metadata and exception causes. Resolve the original payment method for capture using the stored payment. Expose payment-level status and transaction identifiers in a checkout result suitable for the request layer.

**Done when:** A successful checkout response agrees with the in-memory payment and both transaction records. Missing outcomes, persistence errors, declines, and unresolved results have explicit responses rather than falling through as success.

**Depends on:** T5.

### T7 — Make the mock application callable

- [x] Add a runnable application and checkout HTTP endpoint.

**Progress:** The POST controller, request validation, explicit business results, and infrastructure-error response are implemented with Spring MVC tests. The application entry point, default mock adapters, GET endpoint, executable JAR, and documented startup/request commands are implemented. Example account/method UUIDs need no seeding in the current mock.

Provide `POST /api/payments/checkout` accepting account ID, payment method ID, amount, and currency. Wire it to the real core API and checkout workflow. Use documented demo account/payment-method UUIDs directly; catalog validation is outside this milestone. Add `GET /api/payments/{paymentId}` to inspect the payment and its transactions. Document how to select a mock scenario, start the application, and send a sample request.

**Done when:** A documented local command starts the application with only mock adapters. A documented `curl` request returns checkout success, and the read endpoint shows the same final state. Pending results return a processing response; no background completion is promised in this milestone.

**Depends on:** T3, T6.

### T8 — Automate the acceptance checks

- [x] Make the basic checkout acceptance suite run through a standard test command.

**Progress:** `mvn test` now executes JUnit checkout/HTTP tests and the existing contract harnesses. Real service-to-runner authorization/capture tests pass, including REST and persistence failure scenarios. The full HTTP acceptance suite now covers seven success/failure/unresolved scenarios and app restart/read-back, plus invalid requests and missing payments.

Integrate the existing useful checks into an automated test runner, then exercise the real request-to-workflow path with the mock adapters. Ensure the test command actually runs assertions and fails when behavior is incorrect. Keep real-database integration checks separate from the mock-only acceptance suite.

**Done when:** All scenarios below run automatically, and the README contains one startup command, one test command, and a sample checkout request.

**Depends on:** T7. Add focused checks as T1–T7 are implemented rather than waiting until the end.

| Scenario | Required outcome |
|---|---|
| Authorization and capture succeed | One payment succeeds; exactly two successful transactions; authorization precedes capture. |
| Authorization is declined | No capture call; payment reports failure. |
| Authorization provider reports pending | No capture call; transaction and payment report UNKNOWN; provider PENDING is retained. |
| Authorization throws or times out | No capture call; outcome remains unresolved and is not reported as success. |
| Capture is declined | Authorization remains successful; checkout reports failure. |
| Capture is pending or unknown | Checkout remains incomplete; no success response. |
| Intent persistence fails | No gateway call for that operation. |
| Outcome persistence fails | API reports the persistence failure; it does not falsely acknowledge success. |
| Invalid request, missing payment, or wrong account | Rejected before a gateway call. |
| Successful response followed by read | Returned state and stored payment/transaction states agree. |

## Definition of ready

- [x] All eight tasks above are complete.
- [x] I can start the mock application locally with one documented command.
- [x] I can send a checkout request and receive `SUCCEEDED` after authorization and capture.
- [x] I can inspect that payment and its two successful transaction records.
- [x] I can select a failure/pending mock scenario and observe the corresponding outcome.
- [x] The acceptance suite passes without database or network infrastructure.

## Shared retry implementation — completed October 2, 2026

- [x] Add `RetryableOperation`, a shared executor, and validated central defaults with named policy overrides.
- [x] Classify authorization and capture outcomes before retrying; require downstream idempotency protection and respect provider retry prohibitions.
- [x] Bound attempts, per-attempt timeout, total operation elapsed time, exponential backoff, and full jitter; honor `Retry-After` minimum waits.
- [x] Retry only adapter calls using the existing transaction ID, preserving the save-intent-before-side-effect invariant.
- [x] Stop immediate retries on pending/conflict/decline and preserve UNKNOWN when the outcome cannot be confirmed.
- [x] Verify Spring wiring, configuration validation, interruption/deadlines, real REST retries, and full checkout without duplicate financial effects.

The full suite passes 190 tests. This task covers synchronous immediate retries.
Background reconciliation, durable retry budgets, concurrency limits, and real
provider-specific integrations are not part of this implementation.
