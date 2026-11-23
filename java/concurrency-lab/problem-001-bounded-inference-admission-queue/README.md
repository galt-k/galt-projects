# Problem 001: Concurrent Bounded Inference Admission Queue

Build a bounded admission queue in Java 21 for inference requests. You own the design and core concurrency implementation. This scaffold deliberately contains only contracts and disabled test outlines.

## Requirements

- Support 16 concurrent producer threads and exactly one scheduler/consumer thread.
- Capacity is 1024 admitted requests awaiting disposition. Never exceed the bound, including under contention. Define precisely when a cancelled or dispatched request releases capacity in `design.md`.
- Admission returns accepted, rejected-full, or rejected-shutdown. When full or closed, reject immediately; do not wait for space, retry internally until space opens, or silently drop work. Specify the progress guarantee and meaning of immediate under contention.
- Under a running scheduler and graceful shutdown, every accepted request that is not successfully cancelled must be delivered exactly once. Rejected requests must never be dispatched. This is an in-process delivery guarantee, not crash recovery or exactly-once inference execution.
- An accepted submission returns its own cancellation handle. Successful cancellation before dispatch prevents delivery and wasted inference computation. Repeated cancellation must not repeat resource release.
- Cancellation and dispatch must resolve to one winner. If cancellation wins, the scheduler must never receive the request. If dispatch wins, cancellation reports failure and cannot retract delivery. Define the dispatch boundary and demonstrate the race semantics.
- Request data must be safely published to the scheduler. No lost accepted work, duplicate delivery, capacity leaks, or retained cancelled payloads after their defined cleanup point.
- Graceful shutdown closes admission, handles concurrent submissions consistently, and allows accepted noncancelled requests to drain. Cancellation remains meaningful for undispatched accepted work. Shutdown is idempotent; termination is observable once closed and all accepted work has reached a terminal disposition. Empty-but-open is not terminated.
- The scheduler uses `poll()` to attempt one dispatch; an empty result does not by itself signal termination. External scheduling and inference execution are outside this exercise.
- Document ordering, null handling, identity/duplicate-submission rules, caller responsibilities, and cancellation cleanup timing. No FIFO, lock-free, or wait-free requirement is imposed unless you explicitly choose one.

## Work sequence

1. Fill in `design.md`, including invariants, race outcomes, and progress claims.
2. Turn each disabled test outline into assertions and bounded, repeatable concurrency tests.
3. Add your implementation of `AdmissionQueue`; no algorithm is provided here.
4. Run `mvn test` from the repository root. Record results and remaining limitations.
5. Optionally follow `benchmarks/README.md` after correctness is established.

Completion requires enabled tests for saturation, 16-producer contention, cancellation versus dispatch, shutdown races, visibility, and resource accounting. Disabled tests are unfinished work.
