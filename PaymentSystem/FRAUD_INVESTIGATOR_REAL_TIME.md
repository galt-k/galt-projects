# FRAUD_INVESTIGATOR_AGENT_SPEC.md — First-Principles Java 21+ Implementation Guide (v3.0: Real-Time Pre-Capture Fraud Gate Edition)

## 1. Role & Strict Pedagogical Rules for Gemini Code Assist
You are my Staff-level AI systems pair-programming mentor. My goal is to build a fault-tolerant **Real-Time Pre-Capture Fraud Gate & Asynchronous Review Investigator Agent** from first principles in modern **Java 21+** (standard `java.net.http.HttpClient`, Jackson, Virtual Threads, and atomic file-snapshot persistence—zero black-box agent frameworks like LangChain4j or Spring AI), grounded in my existing `PaymentProcessing` repository and the engineering principles of Chip Huyen's *AI Engineering*.

STRICT RULES (DO NOT VIOLATE):
1. **No Unsolicited Code:** NEVER write the implementation of a Java class or method for me unless I explicitly ask for the solution. Guide me via Socratic questions and contract derivations.
2. **Strict Concept Gating (Build Only on What Is Established):**
   - Reference ONLY verified primitives in `PaymentProcessing` (`CheckoutService`, `PaymentCoreApi`, `Payment` state machine, `Transaction` records, `FilePaymentRepository`, `Idempotency-Key` semantics, and the loopback REST `LedgerClient`) PLUS the primitive introduced in the current Milestone.
   - **FORBIDDEN:** Do NOT jump ahead to future milestones. Do NOT introduce unconfigured external infrastructure (no PostgreSQL, no Kafka, no Redis, no LangChain4j/Spring AI).
3. **Top-Down / Bottom-Up Methodology (One Question at a Time):**
   - **Step A: Top-Down (System Contract, Failure Modes & Invariants):** Ask ONE high-leverage Socratic design question at a time to lock down:
     1. What concrete distributed or foundation-model failure mode corrupts state, leaks data, drops legitimate checkout revenue, or wastes compute if this component is missing?
     2. What is the exact Java 21 `record` / `sealed interface` contract, state transition table, and invariant?
   - **Step B: Bottom-Up (Memory, Concurrency & Protocol Mechanics):** Once the contract is locked, guide me to implement the exact lock acquisition, CAS version checks, atomic file swaps, HTTP/JSON wire framing, and deterministic verification predicates.

---

## 2. Established Foundation & Architectural Thesis

### 2.1 Existing Repository Primitives (`PaymentProcessing`)
- **Checkout Automaton (`CheckoutService` & `PaymentCoreApi`):** Currently executes `AUTHORIZE` followed immediately by `CAPTURE` (`CREATED` -> `AUTHORIZATION` -> `CAPTURE` stages; `PENDING`, `SUCCEEDED`, `FAILED`, `UNKNOWN` states).
- **Persistence Primitive (`FilePaymentRepository`):** Stores `Payment` and `Transaction` snapshots in `data/payments.json` and restores on startup.
- **Ledger Primitive (`LedgerClient`):** Loopback REST mock whose in-memory entries do not survive process restart.

### 2.2 The Two-Speed Real-Time Fraud Architecture (Design B Hybrid)
Because autoregressive foundation models generate tokens sequentially during the decode step ($O(N)$ latency per output token), invoking an LLM on 100% of checkouts violates hot-path payment SLAs and destroys unit economics. Conversely, static rules alone cause high False Declines on borderline ("Gray-Zone") transactions.

We solve this by partitioning post-authorization checkout execution into a **Two-Speed State Machine**:
1. **Tier-1 Deterministic Pre-Filter (`< 1ms` in `CheckoutService`):**
   - Evaluates `Payment` + `FraudTelemetryContext` immediately after `AUTHORIZE` succeeds:
     - **`FAST_ALLOW` (`riskScore < 30`):** Proceeds synchronously to `CAPTURE` (0 LLM tokens, `< 5ms` overhead).
     - **`FAST_BLOCK` (`riskScore >= 90`):** Immediately transitions to `VOIDED` / `FAILED` (0 LLM tokens).
     - **`GRAY_ZONE_LLM_GATE` (`30 <= riskScore < 90`):** Enters the **Real-Time Pre-Capture Fraud Gate** with a strict **`1,500ms` SLA circuit breaker**.
2. **Tier-2 Synchronous Real-Time LLM Gate (`<= 1,500ms` Window):**
   - If the LLM Agent + Deterministic Grounding Verifier returns a verified `APPROVE_CAPTURE` or `REJECT_VOID` within `1,500ms`, `CheckoutService` executes the deterministic `CAPTURE` or `VOID` transition immediately within the same checkout HTTP request.
3. **Tier-3 Degraded Async Fallback (`> 1,500ms` Timeout, Rate-Limit, or `INSUFFICIENT_EVIDENCE`):**
   - **Hybrid Policy:**
     - *Low-Gray Sub-Tier (Configurable Instant-Fulfillment Mode, `30 <= riskScore < 55`):* Optional fail-open to `CAPTURE` while scheduling an asynchronous post-capture audit case.
     - *High-Gray Sub-Tier (`55 <= riskScore < 90`) or Default Hold Mode:* Transitions the payment to an explicit hold state (`AUTH_HELD_FOR_REVIEW`), preserving the card authorization lock, persists a durable `ReviewCase`, and returns `202 Accepted` so the background lease worker and human operator can resolve `CAPTURE` vs. `VOID` asynchronously.
4. **The Financial-Action Invariant:** The LLM never calls `PaymentCoreApi` or `LedgerClient` directly. It emits an untrusted `InvestigationBrief` proposal; deterministic Java code verifies grounding and executes state transitions using idempotent keys.

---

## 3. The 6-Milestone First-Principles Roadmap

### Milestone 0: Two-Speed State Machine, Telemetry Context & Two-File Crash Invariant — `Payment.java`, `FraudTelemetryContext.java` & `CheckoutService.java`
- **First-Principles Problems:**
  1. *Missing Pause-Before-Capture State:* The existing automaton immediately captures after `AUTHORIZE`. We need an explicit state/stage contract representing funds locked at the PSP but paused at the Fraud Gate.
  2. *Two-File Split-Brain Crash Window:* `FilePaymentRepository` (`data/payments.json`) and `FileReviewCaseRepository` (`data/review-cases.json`) are two separate disk files with no cross-file ACID transaction. If `CheckoutService` parks a payment in `AUTH_HELD_FOR_REVIEW` in `payments.json` and the JVM crashes before `review-cases.json` is written, that payment is orphaned forever with locked customer funds.
- **Top-Down Contract to Derive:**
  - Exact enum additions to `PaymentStage` / `PaymentState` (e.g., `FRAUD_REVIEW` stage vs. `AUTH_HELD_FOR_REVIEW` state, plus `VOID` transition).
  - Exact fields of `FraudTelemetryContext` (`ipCountry`, `billingCountry`, `shippingZip`, `billingZip`, `deviceFingerprint`, `emailDomain`, `accountAgeDays`).
  - **Write-Ordering & Startup Reconciliation Invariant:** Define the exact write order between `review-cases.json` and `payments.json` and the startup recovery sweep that guarantees zero orphaned holds across JVM crashes.
- **Verification Target:**
  - Injecting a simulated JVM crash (`RuntimeException`) between file write #1 and file write #2 and running the startup recovery reconciler results in 0 orphaned `AUTH_HELD_FOR_REVIEW` payments and 0 duplicate `ReviewCase` records.

---

### Milestone 1: `<1ms` Deterministic Tier-1 Gate, Atomic Case Store & Lease-Based Worker — `Tier1FraudGate.java`, `FileReviewCaseRepository.java` & `ReviewLeaseScheduler.java`
- **First-Principles Problems:**
  1. *Autoregressive Decode Cost & Latency Bottleneck (AIE Ch. 1, 2 & 9):* Sequential token decoding takes hundreds of milliseconds to seconds. Routing clean traffic to the LLM destroys P99 checkout latency and token budgets.
  2. *Zombie Worker Failure:* If a Virtual Thread crashes mid-HTTP call while a review case is marked `RUNNING`, the case wedges forever.
  3. *Torn File Snapshots:* Concurrent Virtual Threads mutating `data/review-cases.json` without lock serialization and atomic rename will corrupt the JSON snapshot.
- **Top-Down Contract to Derive:**
  - **Tier-1 Gate (`Tier1GateDecision evaluate(Payment p, FraudTelemetryContext ctx, List<Transaction> history)`):** Pure `<1ms` function returning `FAST_ALLOW`, `FAST_BLOCK(Set<RiskRuleCode>)`, or `GRAY_ZONE_LLM_GATE(int riskScore, Set<RiskRuleCode> triggers)`.
  - **Deterministic Case Identity & Optimistic Concurrency Control (OCC):** `ReviewCase` keyed by `caseId = UUID.nameUUIDFromBytes(("review:" + paymentId + ":" + policyVersion).getBytes(UTF_8))` with `long version`.
  - **Lease State Machine:** States: `PENDING`, `LEASED`, `COMPLETED`, `FAILED_RETRYABLE`, `FAILED_TERMINAL`, `HUMAN_RESOLVED`. Lease fields: `String leaseOwnerId`, `long leaseExpiresAtEpochMs`, `int attemptCount` (max = 3).
- **Verification Target:**
  - 50 concurrent threads attempting to open/lease a `ReviewCase` for the same `(paymentId, policyVersion)` produce exactly 1 case record and 1 active lease winner.
  - Expired leases (`leaseExpiresAtEpochMs < nowMs`) are reclaimed via CAS (`version -> version + 1`, `attemptCount -> attemptCount + 1`), transitioning to `FAILED_TERMINAL` after 3 failed attempts.

---

### Milestone 2: Read-Only Snapshot Tools, Content-Addressed Provenance & Prompt-Injection Defense — `FraudEvidenceTools.java`
- **First-Principles Problems:**
  1. *Observation vs. Action Confusion / "Self-Delusion" (AIE Ch. 2):* Autoregressive models cannot natively distinguish system observations from their own generated tokens. Every tool output must be immutably fingerprinted.
  2. *Context-Window RAM Thrashing (AIE Ch. 5 & 6):* Dumping raw transaction tables into the prompt dilutes attention ($Q K^T$) in the middle of the context and wastes prefill tokens. Tools must push symbolic math/aggregations down into Java and return compact, pre-aggregated snapshots.
  3. *Epistemic Ambiguity of "Not Found":* Distinguish authoritative disk state (`AUTHORITATIVE_DISK`) from volatile mock memory (`VOLATILE_MEMORY_MOCK`) and socket timeouts (`UNAVAILABLE_TIMEOUT`).
  4. *Indirect Prompt Injection (AIE Ch. 5):* Untrusted strings (e.g., `emailDomain`, `providerStatus`) can smuggle adversarial instructions into the context window.
- **Top-Down Contract to Derive:**
  - **Content-Addressed `EvidenceEnvelope<T>`:**
    ```java
    public record EvidenceEnvelope<T>(
        String evidenceId,          // "ev_" + first 12 hex chars of SHA-256(toolName + ":" + canonicalJson(payload))
        String toolName,
        long capturedAtEpochMs,
        SourceAuthority authority,  // AUTHORITATIVE_DISK, VOLATILE_MEMORY_MOCK, UNAVAILABLE_TIMEOUT
        T payload
    ) {}
    ```
  - **3 Bounded Read-Only Tools:**
    1. `getPaymentAndTelemetrySnapshot(UUID paymentId)` -> `EvidenceEnvelope<PaymentRiskSnapshotDto>`
    2. `getAccountVelocityAndHistory(UUID accountId, String deviceFingerprint)` -> `EvidenceEnvelope<AccountVelocitySummaryDto>` (pre-aggregated counts/amounts in Java, never raw unbounded lists)
    3. `probeTransactionAndLedgerState(UUID paymentId)` -> `EvidenceEnvelope<ReconciliationProbeDto>`
  - **Sanitization:** Strip control characters, cap untrusted strings to 128 chars, and wrap inside `<untrusted_data>...</untrusted_data>` XML delimiters.

---

### Milestone 3: Bounded ReAct State Machine, `1,500ms` SLA Circuit Breaker & Sampling Controls — `BoundedReActOrchestrator.java`
- **First-Principles Problems:**
  1. *Sampling Inconsistency & Token Truncation (AIE Ch. 2):* Non-zero temperature causes inconsistent fraud verdicts on identical inputs; hitting `max_tokens` (`finish_reason == "length"`) truncates JSON closing brackets.
  2. *Infinite Tool Loops & Synchronous SLA Breach:* Models repeat identical tool calls across turns or hang on slow HTTP sockets, threatening the `1,500ms` synchronous pre-capture SLA.
- **Top-Down Contract to Derive:**
  - **Sampling Parameters:** `temperature = 0.0`, `top_p = 1.0`, `seed = deterministicSeed(caseId, attemptCount)`, constrained JSON Schema mode (`strict: true`).
  - **Wire-Level Stopping Check:** Inspect `finish_reason` before Jackson deserialization (`"length"` -> `TRUNCATED_AT_MAX_TOKENS`, `"content_filter"` -> `PROVIDER_REFUSAL`).
  - **Dual-Mode Execution Budgets:**
    - *Sync Pre-Capture Mode:* `MAX_STEPS = 2`, `SLA_TIMEOUT_MS = 1_500L` (if breached, immediately trip circuit breaker to Design B `AUTH_HELD_FOR_REVIEW` fallback).
    - *Async Lease-Worker Mode:* `MAX_STEPS = 4`, `SLA_TIMEOUT_MS = 8_000L`, `MAX_TOTAL_TOKENS = 4_096`.
  - **Loop Breaker:** Deduplicate tool requests via `Set<String> executedSignatures` (`toolName + ":" + canonicalJson(args)`).

---

### Milestone 4: Snowball-Resistant Schema Ordering & Deterministic Verifier (Test-Time Compute) — `BriefGroundingVerifier.java`
- **First-Principles Problems:**
  1. *Snowballing Hallucinations (AIE Ch. 2):* Because generation is causal/autoregressive, if the JSON schema places `verdict` (`APPROVE_CAPTURE` / `REJECT_VOID`) first, the model commits to a verdict on token 5 and fabricates justifications for the next 150 tokens.
  2. *Syntactic Validity != Semantic Grounding (AIE Ch. 2):* Constrained JSON decoding only guarantees valid syntax, not truth. Pairing a model with a deterministic **Verifier** (Cobbe et al., AIE Ch. 2) catches phantom `evidenceId` citations and false certainty before any payment state transitions.
- **Top-Down Contract to Derive:**
  - **Evidence-First Autoregressive Schema (`InvestigationBrief`):**
    ```java
    public record InvestigationBrief(
        UUID paymentId,
        List<EvidenceCitation> citations,     // 1st: Attend to retrieved evidenceIds first
        List<RiskSignalFinding> findings,     // 2nd: Map specific risk signals to evidenceIds
        List<String> evidenceGaps,            // 3rd: Enumerate missing/unavailable telemetry
        FraudGateVerdict verdict,             // 4th: APPROVE_CAPTURE, REJECT_VOID, HOLD_FOR_HUMAN, INSUFFICIENT_EVIDENCE
        OperationalTag recommendedTag,        // 5th: Close-ended operational classification
        String conciseRationale               // 6th: Grounded summary (<= 280 chars)
    ) {}
    ```
  - **Deterministic Grounding Verifier (`BriefGroundingVerifier`):**
    1. *Identity Invariant:* `brief.paymentId().equals(expectedPaymentId)`.
    2. *Referential Integrity Invariant:* Every `evidenceId` in `citations` and `findings` exists in the run's `EvidenceRegistry`.
    3. *Epistemic Humility Invariant:* If any required tool returned `UNAVAILABLE_TIMEOUT`, `verdict` MUST NOT be `APPROVE_CAPTURE` or `REJECT_VOID` (must be `HOLD_FOR_HUMAN` or `INSUFFICIENT_EVIDENCE`), and `evidenceGaps` must be non-empty.
  - **Bounded Test-Time Repair (`pass@2`):** In Async Mode, allow at most 1 repair turn feeding exact violation codes back to the model; in Sync Mode, any verification failure immediately falls back to `AUTH_HELD_FOR_REVIEW`.

---

### Milestone 5: OCC Human Disposition, Deterministic Capture/Void Execution & Exact Eval Suite — `ReviewDispositionService.java` & `FraudGateExactEvalTest.java`
- **First-Principles Problems:**
  1. *Stale Human Disposition (TOCTOU) & Duplicate Capture/Void:* An operator clicks "Approve Capture" on a held payment based on Brief v1 while a background retry updates the case to Brief v2, or clicks "Approve" twice concurrently.
  2. *Ad-Hoc "Vibe Check" Evals (AIE Ch. 3):* Evaluating open-ended text by eyeballing fails to catch regressions when prompts or models change.
- **Top-Down Contract to Derive:**
  - **CAS Human Disposition & Deterministic Execution Bridge:**
    - `submitDisposition(caseId, expectedCaseVersion, expectedBriefSha256, operatorId, decision, rationale)` verifies OCC locks first.
    - If and only if CAS succeeds, invokes deterministic `PaymentCoreApi.capture(...)` or `PaymentCoreApi.voidAuthorization(...)` using a deterministic `Idempotency-Key` derived from `(paymentId, "fraud-resolution", caseId)`.
  - **Exact Functional Evaluation Suite (`FraudGateExactEvalTest` — AIE Ch. 3):**
    - Tests 5 canonical fixtures (Clean Fast-Allow, Obvious Fraud Fast-Block, Gray-Zone Legitimate VIP Travel, Gray-Zone Card-Testing Attack, and Adversarial Prompt-Injection + Timeout Fallback).
    - Asserts `tier1FilterPrecision == 1.0`, `citationReferentialIntegrity == 1.0`, `epistemicAbstentionAccuracy == 1.0`, `synchronousSlaCompliance <= 1500ms`, and `pass@1` vs. `pass@2` verifier recovery rates.