# Problem 002: Concurrent Inference Token Budget

Java 21 review exercise. The intentional stale release-budget defect has been corrected; full verification remains pending.

A single-JVM budget contains 10,000 abstract token units. These model resource reservations, not a time-based rate limiter or actual GPU-memory accounting. Multiple callers reserve variable amounts before work and release them afterward.

`reserve(units)` returns an optional reservation: empty means insufficient budget at the decision point. Invalid amounts throw IllegalArgumentException. A successful handle exposes its fixed amount and `release()`. Exactly one release returns true; repeated release returns false, with the units already restored by the successful release. There are no timeouts, expiry, shutdown, or FIFO requirements here.

Operations do not wait for resources or another release owner. Interference may cause retries; no per-caller bounded-step or fairness guarantee is claimed. Immutable snapshot copying and allocation are intentional teaching simplifications, not a throughput optimization. Handles retain their budget owner and identity, but the budget retains only active reservation records.

## Review order

1. Read `review-exercise.md`.
2. Inspect `TokenBudget.java`: reservation, release, and the state they share.
3. Trace overlapping operations. Identify the violated invariant and a concrete history before proposing a fix.

Build from the repository root with `mvn -pl problem-002-inference-token-budget -am compile`. Compilation is not correctness verification. No solution or defect trigger is included in this worksheet.
