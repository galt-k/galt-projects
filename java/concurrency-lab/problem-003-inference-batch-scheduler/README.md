# Problem 003: LLM Inference Request Scheduler

> **Current design:** see the [LLM Inference Request Scheduler design](design.md) for the integrated dispatch flow,
> ownership invariants, verification status, and outstanding lifecycle decisions.
> The waiting-slice description below documents the original exercise scope.

Java 21 review exercise. The intentional cancellation window-extension defect has been corrected. Review against `review-exercise.md`; this is not production-ready.

## Scope

Multiple producer and cancellation threads share a queue with exactly one scheduler thread. Producers call `submit`, retaining a cancellation handle. The scheduler calls `takeBatch`, which waits until a nonempty FIFO prefix is ready, then atomically hands that batch to the scheduler caller. Execute inference only after this method returns, outside the internal lock.

- At most 8 requests and 4,096 estimated token units per batch.
- Each individual request must have a valid ID and cost from 1 through 4,096.
- The first pending request opens a fixed 5-ms forming window. Later arrivals and cancellation of its original first request must not restart or extend that window while other work remains.
- If all pending work is cancelled, discard the window; the next arrival opens a new one.
- If the next FIFO request cannot fit, dispatch the preceding prefix immediately. Do not bypass the non-fitting request.
- Reaching either batch limit also makes the batch ready immediately.
- Otherwise dispatch when the window expires. This is a readiness threshold, not a real-time execution guarantee: thread scheduling and lock contention can delay actual handoff.
- After a prefix is handed off, residual work forms the next batch using the oldest remaining request's admission time, preserving time already spent waiting.
- Cancellation succeeds only while that particular submission is pending; once selected and removed for handoff it is too late. Repeated cancellation returns false.
- Interruption while acquiring the scheduler lock or waiting propagates InterruptedException without removing pending requests. Interruption is cooperative, not an asynchronous rollback of a completed handoff.
- Wakeups cause state rechecking; they are not permission to dispatch.

This slice intentionally excludes execution slots, token-budget integration, soft first-token targets, actual inference, bounded admission, shutdown, and distributed coordination. Request IDs are descriptive labels here, not duplicate-admission keys; handles use distinct per-submission identity. Queue-level cancellation cannot stop already handed-off work. Caller payloads must remain unchanged while pending.

## API

`submit(Request)` returns a `CancellationHandle`.

`takeBatch()` is interruptible and returns a nonempty immutable `Batch`. Invoke it from one scheduler thread only. To stop an idle scheduler in this slice, interrupt its thread; there is no shutdown API yet.

Build from the root: `mvn -pl problem-003-inference-batch-scheduler -am compile`.

## Review path

Read the contract, then inspect `submit`, `cancel`, and `takeBatch` together. Trace shared-state changes across calls, including cancellation and timer events. Write findings in the worksheet before asking for hints.

## Performance investigation

The [local incident exercise](performance-investigation/README.md) adds baseline and intentionally defective scheduler simulations, metrics, event timelines, and optional JFR recording. It is separate from the waiting-slice implementation above. Start with the measurements and [investigation worksheet](performance-investigation/investigation.md) before inspecting its source.

```bash
cd problem-003-inference-batch-scheduler/performance-investigation
bash run.sh baseline
bash run.sh incident
```

## Deadlock investigation

The [scheduler and memory-budget review exercise](deadlock-investigation/README.md) contains an intentional coordination defect, a repeatable harness, thread diagnostics, and a [worksheet](deadlock-investigation/investigation.md). It leaves the existing scheduler unchanged.
