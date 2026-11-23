# Review worksheet: scheduler waiting and batch readiness

The intentional cancellation window-extension defect has been corrected. Cancellation now preserves the forming window while requests remain pending. Compilation and basic checks do not establish correctness. Identify the violated contract, show an operation history, and explain a minimal correction.

## Invariants

1. Every handoff is nonempty, contains at most 8 requests, and totals at most 4,096 estimated token units.
2. FIFO is preserved among pending noncancelled submissions; a non-fitting request stays first for the next batch.
3. A batch becomes ready when a limit is reached, the next FIFO request cannot fit, or its fixed forming window expires.
4. The forming window is not extended by arrivals or cancellation while work remains. An empty queue discards its old window. Residual work after dispatch retains its already-spent waiting time.
5. Checking state and entering a wait cannot miss a producer or cancellation signal. Waiting releases the lock; waking reacquires it and rechecks state.
6. Idle and underfilled states do not busy-poll. Timed waits use the remaining time, not a fresh interval after every wakeup.
7. Cancellation and handoff have exactly one winner per submission. Successful cancellation excludes delivery; stale handles cannot affect later submissions.
8. Cancellation does not dispatch unrelated requests or reorder surviving ones. Cancelled entries are removed immediately.
9. Interruption during waiting or lock acquisition exits without consuming pending work; it does not cancel requests on behalf of their callers.
10. Queue contents, forming-window state, and batch removal are coordinated by the same lock. No inference or external callback runs while holding it.

## Your review

- Invariants preserved and why: TODO
- Invariant violated: TODO
- Exact operation history: TODO
- Expected versus actual behavior: TODO
- Proposed correction: TODO
- Regression test: TODO
