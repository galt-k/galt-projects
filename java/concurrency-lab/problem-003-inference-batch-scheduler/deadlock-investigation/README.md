# Problem 3: scheduler / memory-budget investigation

Review exercise with one intentional coordination defect, not production code. This is an isolated simulation beside the existing scheduler, not its completed execution integration. No GPU or external dependencies required; use JDK 21.

## Scenario and contract

Attempt A already owns one of two execution slots and 256 of 1,024 simulated KV-cache blocks. One worker dispatches B while another reports completion of A. Each attempt has a unique ID. Memory is sufficient for both; exhaustion is not expected in this scenario.

Required invariants:
- At most two active attempts, with memory use between zero and 1,024 blocks.
- Successful dispatch records slot ownership and a matching memory reservation.
- Completion retires the matching attempt and returns its blocks exactly once.
- Duplicate completion does not release resources again.
- Once these two operations finish, A owns nothing and B owns one slot and 256 blocks.
- Dispatch and completion must eventually return when capacity is sufficient and threads are scheduled.
- Interruption before committing these scenario operations must not leave partial resource ownership.

## Run

```bash
bash run.sh serial
bash run.sh incident
```

The launcher works from any directory when given its absolute path. Each run preserves artifacts in a fresh runs/ directory. Serial mode is a sanity check, not proof of concurrent correctness. The incident uses bounded test checkpoints to expose an otherwise timing-dependent interaction. Those checkpoints are harness machinery, not a production synchronization strategy. They expire after 500 ms, so serializing candidate fixes need not deadlock on the checkpoint itself.

Inspect DeadlockLab.java: Scheduler and MemoryBudget are the review targets. Read the harness only to understand the forced timing. Do not remove checkpoints as a substitute for fixing production coordination.

Start with summary.txt and metrics.csv. threads.txt contains stronger diagnostic evidence; open it after writing an initial hypothesis. Metrics counters include initial admission of A. No CPU or GPU utilization is measured. The JVM detector covers platform-thread monitor/ownable-synchronizer cycles, not every possible liveness failure.

The watchdog records evidence and interrupts workers after detecting a stable cycle (or after six seconds). Workers are daemon threads as a last-resort safeguard; the harness checks that they actually exit. Accounting is audited afterward. A passing cleanup check does NOT mean the incident was correct. Exit status zero means the harness collected its result, which can be STALLED.

## Review process

1. Identify the violated invariant and write a possible event ordering.
2. Inspect thread stacks, owned locks, and awaited locks to support or reject that hypothesis.
3. Propose a structural change and explain why the problematic ordering becomes impossible.
4. Request or implement the fix only after recording your reasoning.
5. Rerun both modes; expect COMPLETE, no detected cycle, and passing accounting/cleanup checks.

No solution is included. Passing this small scenario is not an exhaustive proof for retries, arbitrary failures, real GPU work, or cancellation after dispatch.
