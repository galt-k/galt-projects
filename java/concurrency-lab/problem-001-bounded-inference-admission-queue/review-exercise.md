# Review exercise: cancellation, dispatch, and lifecycle

This implementation intentionally contains one correctness defect. Do not treat it as production-ready. Identify the violated invariant, give an exact operation history that demonstrates it, and propose the smallest justified correction. No defect location or trigger is supplied here.

## Invariants to review

1. Occupancy is between 0 and 1024 inclusive and equals the number of pending nodes and the size of each lookup map.
2. Each pending request ID identifies exactly one accepted submission. Duplicate rejection consumes no capacity. Rejection precedence for valid input is duplicate, shutdown, then full.
3. A cancellation handle identifies one acceptance, even after request IDs are reused. It must never affect another submission.
4. Cancellation and dispatch have exactly one winner. Successful cancellation prevents delivery; dispatch makes later cancellation fail.
5. Each accepted submission releases capacity and its request ID exactly once, on successful cancellation or dispatch. Failed and repeated cancellation changes nothing.
6. Every accepted, noncancelled request is dispatched exactly once if the scheduler continues draining. FIFO follows acceptance order among remaining requests.
7. Both lookup maps and the doubly linked queue describe the same pending submissions. Head/tail, neighbor links, empty and singleton cases remain consistent.
8. Cancellation unlinks its node before returning success. The queue retains no accumulating terminal-node history, and handles do not retain original nodes or payloads.
9. All shared queue-state reads and changes use the same lock. Publication, accounting, identity reservation, and lifecycle transitions are coordinated. Inference runs outside the queue.
10. Shutdown is idempotent and closes admission. Accepted work drains, and cancellation remains available while draining.
11. Termination means closed admission and no pending work. Shutdown of an empty queue, final dispatch, and final cancellation must establish termination as appropriate. Empty but open is not terminated.
12. No operation waits for space or data. Lock acquisition may wait; this implementation is not lock-free.

## Scope and explicit assumptions

The caller keeps ID and payload unchanged while pending;
null request and missing/blank ID validation consume no capacity.
Submission IDs are queue-local.
This scaffold throws before acceptance on identity-space exhaustion instead of allowing numeric overflow.
Ordinary operation correctness is the review scope; recovery from VM resource exhaustion is not implemented.
Existing disabled tests do not establish correctness.

## Your review

- Violated invariant: TODO
  - #1- Yes 
  - #2- Yes
  - #3- Yes
  - #4- Yes
  - #5- Yes
  - #6- Yes
  - #7- 
- Operation history / thread interleaving: TODO
- Expected versus actual result: TODO
- Proposed correction and why it preserves other invariants: TODO
- Regression test: TODO
