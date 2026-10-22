# CHECKOUT_ARCHITECTURE_SPEC.md — First-Principles Frontend Systems Guide (SE-FE01 – SE-FE07)

## 1. Role & Strict Pedagogical Rules for Gemini Code Assist
You are my AI pair-programming systems mentor. My goal is to build an enterprise-grade, PCI-DSS SAQ-A compliant frontend checkout architecture from first principles in **modern React 19 + TypeScript + Vite + Vitest**, grounding every design decision in what physically executes inside **Chrome V8** (Call Stack, Lexical Closures, Fiber Heap, Microtask/Macrotask Queues, Type Erasure) and **Blink C++** (DOM tree, WebIDL bindings, Site Isolation, Compositor Thread).

### STRICT RULES (DO NOT VIOLATE):
1. **No Unsolicited Code:** NEVER hand me the finished implementation or component upfront. You must always start by challenging me with 1–2 diagnostic Socratic questions to verify my understanding of the **Top-Down Systems Invariant** and the **Engine Failure Modes**.
2. **Top-Down / Bottom-Up Methodology:** For every milestone or task, strictly enforce two phases:
  - **Step A: Top-Down (System Contract & First-Principles "Why"):** Define the primary security/concurrency invariant and the exact failure mode we are defeating before writing any code.
  - **Step B: Bottom-Up (Engine & Framework Primitives):** Implement the Blink C++, TypeScript, and React primitives required to satisfy the invariant.
3. **Strict Concept Gating:** Do not jump ahead to future pillars until the active pillar is verified with passing Vitest suites.

---

## 2. Established Foundation (Completed Core Work)
- **SE-FE01 (Tasks 1.1 – 1.4 Completed — 6 Vitest Tests Passing):**
  - **`src/types/protocol.ts` (Tasks 1.1 & 1.4):** Discriminated union wire protocol defining outbound vault requests and inbound token responses with mandatory `correlationId`.
  - **Runtime Type Guards (Task 1.2 & 1.4):** `isVaultOutboundMessage` validating untrusted JSON arriving via `window.postMessage` at the V8 ingress gate, defeating compile-time TypeScript Type Erasure and validating `correlationId: string`.
  - **Secure Iframe Sandboxing & Concurrency Guard (Tasks 1.3 & 1.4):** `<SecureCardElement />` utilizing Blink's Site Isolation, SOP origin/source verification, and `activeCorrelationIdref` matching on the Fiber heap to drop stale/out-of-order postMessage replies.

---

## 3. The 7-Pillar First-Principles Roadmap

### Pillar 1: Trust Boundary & PCI-DSS Reduction (`SE-FE01`)
- **Status:** **Completed (6 Vitest tests passing)**
- **First-Principles Problem:** The host merchant application shares the V8 heap and Blink DOM with third-party scripts. Allowing raw cardholder data (PAN, CVV) to touch the host DOM instantly escalates PCI scope to SAQ-D and invites Magecart scraping attacks. Furthermore, `window.postMessage` is an asynchronous broadcast channel; a delayed response from an earlier tokenization attempt can collide with a new checkout request.
- **Top-Down Contract & Tasks:**
  - **Task 1.1:** Protocol contract in `protocol.ts`. *(Completed)*
  - **Task 1.2:** Runtime Type Guard `isVaultOutboundMessage`. *(Completed)*
  - **Task 1.3:** `<SecureCardElement />` iframe host with SOP checks. *(Completed)*
  - **Task 1.4 (Correlation ID Upgrade):** Add `correlationId: string` to messages; reject mismatched/stale correlation IDs on the Fiber heap. *(Completed — Verified with 6 passing Vitest tests)*

---

### Pillar 2: Deterministic Finite State Machine & Concurrency (`SE-FE02`)
- **Status:** **In Progress**
- **First-Principles Problem:** When a user double-clicks "Pay", Blink enqueues two click tasks in V8's **Macrotask Queue**. React state updates are asynchronous and schedule re-renders rather than synchronously updating lexical closures. This allows Click #2 to execute against a stale closure (`status === 'IDLE'`), firing duplicate financial mutations. Independent boolean flags (`isLoading`, `isError`) permit $2^3 = 8$ states, creating corrupted UI conditions.
- **Top-Down Contract:**
  - **State Model:** Exhaustive Discriminated Union for `CheckoutState` (`IDLE` | `TOKENIZING` | `SUBMITTING_PAYMENT` | `REQUIRES_3DS` | `SUCCEEDED` | `FAILED`) and `CheckoutAction`. Zero independent booleans allowed.
  - **Concurrency Primitives:** Combine `useReducer` (pure state transition table) with a synchronous `useRef<boolean>(false)` (`inFlightMutexRef`) checked and mutated on Line 1 of the submit handler to block same-tick Macrotask clicks before React schedules a re-render.
  - **Network Caching:** `AbortController` (`signal`) to physically cancel in-flight C++ network sockets if the state machine resets.
- **Verification Tasks:**
  - **Task 2.1:** Design `CheckoutState` and `CheckoutAction` in `checkoutReducer.ts`. *(Completed)*
  - **Task 2.2:** Implement pure `checkoutReducer` with exhaustive `default: assertNever` check and referential equality on no-ops. *(Completed)*
  - **Task 2.3:** Build `useCheckoutMachine.ts` combining `useReducer`, synchronous `inFlightMutexRef`, and `AbortController`. *(In Progress)*
  - **Task 2.4:** Write Vitest suite (`checkoutReducer.test.ts` & hook concurrency suite) proving invalid transitions return exact state references and firing `submit()` 5 times synchronously dispatches exactly 1 network request. *(Pending)*

---

### Pillar 3: Distributed Client Idempotency Scoping (`SE-FE03`)
- **Status:** Planned (Depends on SE-FE02)
- **First-Principles Problem:** Network ambiguity (timeouts, `503` errors) requires retrying checkout requests. If a retry uses a *new* UUID, the backend processes a duplicate charge. If a retry reuses the *old* UUID after the user edited the `amount` or after a terminal business decline (`402 Card Declined`), the backend throws an `HTTP 409 Conflict` or returns a cached failure. React's `useMemo` is a semantic cache subject to eviction and cannot be trusted for idempotency keys.
- **Top-Down Contract:**
  - **UUIDv4 Lifecycle:** Managed via `useRef<IdempotencyRecord>` on the React Fiber heap (immune to `useMemo` cache eviction).
  - **Payload Fingerprinting:** Compute canonical string of `[amount, currency, token]`.
  - **Rotation Rules:** Mint a new UUIDv4 if `currentFingerprint !== ref.current.fingerprint` OR `ref.current.status === 'CONSUMED_TERMINAL'`. Preserve UUIDv4 on `5xx` / network timeout retries.
- **Verification Tasks:**
  - **Task 3.1:** Implement canonical payload fingerprinting (`computeFingerprint`).
  - **Task 3.2:** Build `useIdempotentCheckout.ts` tracking `{ key, fingerprint, status }` in `useRef`, exposing `markRetryableFailure()` and `markTerminalFailure()``.
  - **Task 3.3:** Write Vitest suite verifying re-renders preserve UUID, `503` retries preserve UUID, amount mutations rotate UUID, and `402` terminals rotate UUID on retry.

---

### Pillar 4: Asynchronous 3D Secure (SCA) & Session Hydration (`SE-FE04`)
- **Status:** Planned (Depends on SE-FE02, SE-FE03)
- **First-Principles Problem:** A full-page 3DS redirect or browser tab refresh (`Cmd+R`) completely destroys the V8 execution context and React Fiber heap (`useState`, `useReducer`, `useRef` wiped to zero). The client must deterministically reconcile the in-flight transaction on mount rather than rendering a blank checkout form and double-charging the user. Synchronous `sessionStorage` reads directly in component bodies stall every re-render across the V8 IPC boundary.
- **Top-Down Contract:**
  - **Storage & Navigation:** `window.sessionStorage` + URL query params (`?payment_intent=pi_xxx`).
  - **Runtime Type Guard:** `isPersistedCheckoutSession` validating storage strings at the V8 ingress gate.
  - **Lazy Initialization:** `useState<SessionState>(() => readAndValidateSession())` ensuring React executes synchronous storage reads *exclusively on Render #1 (mount)*.
  - **Exponential Backoff Polling:** `useEffect` managing polling loop (`delay = Math.min(maxDelay, base * 2 ** attempt) + randomJitter`) with strict `AbortController` + `clearTimeout` cleanup return functions.
- **Verification Tasks:**
  - **Task 4.1:** Define `PersistedCheckoutSession` schema and runtime Type Guard.
  - **Task 4.2:** Implement `<CheckoutSessionHydrator />` with lazy `useState` initialization checking storage and URL params.
  - **Task 4.3:** Implement `usePaymentPolling.ts` with exponential backoff, jitter, circuit breaker, and cleanup.
  - **Task 4.4:** Write Vitest suite simulating page reload recovery into `VERIFYING_PAYMENT`, corrupted storage fallback, and zero network calls firing after unmount (`vi.useFakeTimers()`).

---

### Pillar 5: Cross-Tab Split-Brain & Distributed Browser Locking (`SE-FE05`)
- **Status:** Planned (Advanced Systems Track)
- **First-Principles Problem:** A user opening the cart in Tab A and Tab B simultaneously can click "Pay" in both tabs. Because Tab A and Tab B have isolated V8 heaps and separate `sessionStorage` instances, a single-tab `inFlightMutexRef` cannot prevent cross-tab double payments.
- **Top-Down Contract:**
  - **Web Locks API:** `navigator.locks.request('checkout_submission_lock', { ifAvailable: true }, callback)` for OS-level cross-tab mutex coordination.
  - **Broadcast Channel API:** `BroadcastChannel('checkout_sync_channel')` for zero-latency cross-tab state pub/sub (e.g., notifying Tab B when Tab A succeeds).
- **Verification Tasks:**
  - **Task 5.1:** Implement cross-tab lock acquisition in `useCheckoutMachine.ts`.
  - **Task 5.2:** Build `BroadcastChannel` synchronizer to instantly transition all open tabs to `SUCCEEDED` upon payment confirmation.

---

### Pillar 6: Blink Rendering Pipeline & Layout Containment (`SE-FE06`)
- **Status:** Planned (Advanced Systems Track)
- **First-Principles Problem:** Dynamic DOM mutations (like error banners rendering above the card input) trigger global Blink C++ Layout Reflow cascades, causing the "Pay" button to shift under the user's mouse (Cumulative Layout Shift / CLS) and risking accidental clicks.
- **Top-Down Contract:**
  - **Layout Containment:** Apply CSS `contain: layout paint;` and pre-allocate explicit bounding box skeleton geometry (`min-height`) for error containers and the `<SecureCardElement>` iframe (`CLS === 0.000`).
  - **GPU Compositor Thread:** Keep animations (spinners) on the compositor thread (`transform` / `opacity`) to maintain locked 60 FPS during heavy V8 validation tasks.
- **Verification Tasks:**
  - **Task 6.1:** Apply layout containment rules and fixed skeleton bounding boxes to the checkout form.
  - **Task 6.2:** Verify zero layout shift during asynchronous error banner insertion.

---

### Pillar 7: Distributed Trace Propagation & Lossless Telemetry (`SE-FE07`)
- **Status:** Planned (Advanced Systems Track)
- **First-Principles Problem:** When a user closes a tab (`window.unload` / `visibilitychange`) during a failed checkout, Blink C++ immediately aborts standard `fetch` sockets, silently dropping error telemetry. Furthermore, backend Java ledger sagas and frontend React state machines are disconnected without a shared correlation header.
- **Top-Down Contract:**
  - **W3C Trace Context:** Generate a 128-bit `traceId` at checkout initiation and propagate via `traceparent` headers to link React state transitions with backend OpenTelemetry spans.
  - **Lossless Beacon:** Utilize `navigator.sendBeacon()` or `fetch(..., { keepalive: true })` on `visibilitychange` so the browser's C++ network process outlives the V8 renderer process during tab closure.
- **Verification Tasks:**
  - **Task 7.1:** Implement W3C `traceparent` header injection in network wrappers.
  - **Task 7.2:** Build `useCheckoutTelemetry.ts` utilizing `keepalive: true` and `visibilitychange` listeners.

---

## 4. Ground-Truth Test Fixtures & Verification Data

### A. SE-FE01 Vault Message Protocol (`src/types/protocol.ts`)
```typescript
export type VaultOutboundMessage =
  | { type: 'TOKENIZE_REQUEST'; correlationId: string }
  | { type: 'TOKEN_SUCCESS'; correlationId: string; token: string; cardLast4: string; cardBrand: string }
  | { type: 'TOKEN_ERROR'; correlationId: string; code: string; message: string };

export function isVaultOutboundMessage(data: unknown): data is VaultOutboundMessage {
  if (typeof data !== 'object' || data === null) return false;
  const msg = data as Record<string, unknown>;
  if (typeof msg['type'] !== 'string' || typeof msg['correlationId'] !== 'string') return false;
  
  switch (msg['type']) {
    case 'TOKENIZE_REQUEST':
      return true;
    case 'TOKEN_SUCCESS':
      return typeof msg['token'] === 'string' && typeof msg['cardLast4'] === 'string';
    case 'TOKEN_ERROR':
      return typeof msg['code'] === 'string' && typeof msg['message'] === 'string';
    default:
      return false;
  }
}
```

### B. SE-FE03 Fingerprinting Fixture
```typescript
export function computeFingerprint(amount: number, currency: string, token: string): string {
  return `${amount}:${currency.toUpperCase().trim()}:${token.trim()}`;
}
```
