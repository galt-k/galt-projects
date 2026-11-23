# Review exercise: waiting for graceful termination

One intentional defect exists in the new termination-waiting behavior. Find it by tracing caller histories against the invariants below. The previously fixed submission-identity defect remains fixed.

## API

`awaitTermination(Duration timeout)` returns true on termination or false on timeout. It does not initiate shutdown. Null timeouts throw NullPointerException; negative timeouts throw IllegalArgumentException. Zero checks the current state without waiting for termination. Interruption throws InterruptedException and affects only the interrupted caller. Large positive durations saturate to Long.MAX_VALUE nanoseconds. Waiting does not promise exact wall-clock scheduling deadlines.

## Invariants

1. Only the queue establishes termination, when admission is closed and no pending submissions remain.
2. Completion is remembered: callers arriving after termination observe success.
3. Each caller has an independent timeout budget. One caller timing out must not finish another caller's wait or alter the shared completion state.
4. All current waiters become eligible to resume on termination; execution order is unspecified.
5. Waiting does not retain the shared queue lock and prevent cancellation, dispatch, or shutdown from progressing.
6. Interruption affects only that caller and does not cancel the queue or the shared signal.
7. Waiting never initiates shutdown or changes pending requests, capacity, or identity mappings.
8. Every route into TERMINATED signals completion: shutdown of an empty queue, final dispatch while draining, and final cancellation while draining.

The implementation uses a private completion mechanism. Callers receive only the waiting API, not control over that mechanism. The exercise is not production-ready. Existing disabled tests are not evidence of correctness.

## Your review

- Invariant violated: TODO
  - #1 - Yes-preserved
  - #2 -  Yes
  - #3 - 
  - #4 - 
  - #5 - Yes
  - #6 - Yes
  - #7 - 
- Exact operation history: TODO
- Expected versus observed behavior: TODO
- Proposed correction: TODO
- Regression test: TODO

## Correction applied

Caller timeouts now use timed `get`, returning false on TimeoutException without completing the shared future. Only queue termination completes that future. The original intentional defect has been corrected; the review prompts above are retained as exercise history.
