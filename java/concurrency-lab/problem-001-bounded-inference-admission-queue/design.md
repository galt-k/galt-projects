# Design worksheet and engineering-practice policy

## First task: cancellation and dispatch contract

Start here. Write your answers in plain English; no concurrency implementation is needed yet.

Your earlier stated priority: cancellation before dispatch should prevent wasted inference computation and resource leaks.

1. What does **dispatch** mean—the exact point after which cancellation is too late?

   Answer: Dispatch is the point at which the scheduler commits to accepting the request for processing. This commitment is the irreversible boundary, not a notification sent afterward. Once the scheduler commits, cancellation is too late.

2. If cancellation and dispatch happen concurrently, what outcomes are allowed?

   Answer: Exactly one wins. If cancellation succeeds before the scheduler commits, the request must not be dispatched. If the scheduler commits first, cancellation fails and the request is dispatched. Successful cancellation and dispatch must never both occur for the same accepted submission.

3. When does a cancelled request release its capacity slot?

   Answer: Successful cancellation releases the request's capacity slot. By the time cancellation returns success, that slot must be available for reuse. Another producer may claim it first, so a particular new submission may still be rejected if the queue fills again. Capacity release does not depend on delivery of a response to a remote client.

## Next task: lifecycle of one accepted request

Define the request lifecycle first; the queue lifecycle remains a later design decision. Write in plain English without choosing locks, atomics, or an implementation.

1. Which states can an accepted request be in?

   Answer: ACCEPTED, CANCELLED, DISPATCHED. These describe the conceptual lifecycle within the admission queue; they do not require a particular state field or implementation. REJECTED is an admission outcome, not a state of an accepted request. Inference execution and completion are outside this queue's responsibility.

2. Which transitions between those states are allowed?

   Answer: ACCEPTED → CANCELLED and ACCEPTED → DISPATCHED. Exactly one of these transitions may succeed for an accepted submission. A cancelled request cannot later be dispatched, and a dispatched request cannot later be successfully cancelled.

3. Who can cause each transition—the cancellation caller or the scheduler?

   Answer: The cancellation caller can cause ACCEPTED → CANCELLED. The single scheduler can cause ACCEPTED → DISPATCHED by committing to accept the request for processing.

4. Which states are terminal, meaning no further transitions are allowed?

   Answer: CANCELLED and DISPATCHED are terminal within the queue's lifecycle. DISPATCHED does not mean inference processing has completed.

### Transitions and capacity effects

```text
States: ACCEPTED, CANCELLED, DISPATCHED

Transition: ACCEPTED → CANCELLED
Triggered by: Cancellation caller, if cancellation wins before scheduler commitment.
Capacity effect: Releases one slot exactly once. By the time cancellation returns
success, the slot is available for reuse (or may already have been claimed by
another producer).

Transition: ACCEPTED → DISPATCHED
Triggered by: Scheduler committing to accept the request for processing.
Capacity effect: Releases one slot exactly once at dispatch. The dispatched
request no longer counts toward the queue's capacity.
```

Each accepted submission releases its slot through either successful cancellation or dispatch, never both. Failed or repeated cancellation does not release another slot. If occupancy was 1024, dispatch reduces it to 1023 unless concurrent admission fills the freed slot. This specifies accounting behavior without choosing a shared counter or synchronization algorithm.

## Current engineering task: shared-state inventory

What information must your queue track to enforce the agreed invariants, which threads access that information, and which changes must happen together?
* Capacity, 

Before choosing locks, atomics, or other Java 21 concurrency tools, fill in the table below. Add as many rows as needed. Identify the information conceptually; you do not need to select concrete data structures yet.

| Information to track | Who reads it? | Who changes it? | Which changes must happen together? |
| --- | --- | --- | --- |
| Capacity | Clients or producer threads | Producer threads | TODO |
| Terminated | Producer threads | Internal implmentation | 
| State | producers & Scheduler | Producers and scheduler | 
| Requests | Producers and Scheduler| producer and scedulers|  
| Duplicate| Producers | internal impl|
| Request States | Producers & Schedulers | Internal

To add the request, Capacity check and request addition to the queue should happen atomically.
Capacity -> If another thread doesnt see the latest capacity then, capcity limit invariant breaks.


For each row, explain which invariant could break if another thread observed only part of an update.

Answer: TODO

You own the shared-state, atomicity, synchronization, visibility, and correctness decisions. Assistance can review your reasoning with questions and counterexamples without providing the core concurrency implementation.

## Practice policy

The learner writes the concurrency algorithm and implementation. Assistance may clarify contracts, review reasoning, or discuss observed failures; it must not supply the solution without an explicit change in scope. Do not select a synchronization strategy on the learner's behalf.

Write down the contract before coding. Make one justified change at a time. Use assertions, bounded waits, reproducible seeds, and explicit thread cleanup. Avoid sleep-based claims of correctness. Never treat a passing stress test or benchmark as proof. Record failures honestly; do not disable a failing test to claim completion. Benchmark only after correctness checks.

## Decisions to fill in

- What are the request and queue lifecycle states? Request: complete the next-task section above. Queue: TODO
- Where does each operation take effect atomically? TODO
- What exactly counts toward capacity, including cancellation cleanup? TODO
- What is the cancellation-versus-dispatch decision boundary? TODO
- What return values are possible for repeated cancellation? TODO
- How do admission and shutdown races resolve? TODO
- What publication/visibility guarantees are required, and why? TODO
- What ordering and fairness guarantees will you provide? TODO
- What is the progress bound for admission under contention? TODO
- How are nulls, duplicate payloads, and submission identity handled? TODO
- Who drives draining and how is termination observed? TODO
- When are payload references and any owned resources released? TODO
- What scheduling assumptions are needed for liveness? TODO

## Invariants: provide definitions, argument, and test evidence

| Required property | Precise statement / justification | Test evidence |
| --- | --- | --- |
| Capacity stays between 0 and 1024 | TODO | TODO |
| Each accepted submission has one terminal disposition | TODO | TODO |
| Successful cancellation excludes dispatch | TODO | TODO |
| Rejected submissions never dispatch | TODO | TODO |
| Dispatch occurs at most once per accepted submission | TODO | TODO |
| Noncancelled accepted work eventually drains under stated assumptions | TODO | TODO |
| Capacity and payload ownership are released exactly once | TODO | TODO |
| Closed admission never reopens | TODO | TODO |
| Termination implies closed and no remaining accepted work | TODO | TODO |

## Race analysis

For admission/full, admission/shutdown, cancel/dispatch, cancel/cancel, and shutdown/last-dispatch: enumerate allowed outcomes, forbidden outcomes, and a test that distinguishes them. TODO

## Verification record

Commands, JDK, seeds, repetitions, observed failures, fixes, and limitations: TODO
