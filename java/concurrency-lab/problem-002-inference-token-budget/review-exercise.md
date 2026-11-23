# Review: reservation ownership and budget accounting

The intentional stale release-budget defect has been corrected. Release now recalculates the proposed budget from the latest observation on every retry. Review the entire operation, including retries.

## Invariants

1. Available units stay between 0 and 10,000 inclusive.
2. Available units plus the sum held by active reservations always equal 10,000.
3. Reservation commits its budget deduction and active record together, or changes neither.
4. Release commits restoration and active-record removal together, or changes neither.
5. Each accepted reservation has its own identity and fixed amount; old handles never affect new reservations.
6. Units are restored exactly once. Repeated release does not change accounting and returns only after restoration has taken effect.
7. Concurrent operations preserve each other's committed changes; interference cannot lose a reservation or a budget update.
8. The component retains no completed-reservation history.
9. A paused caller does not exclusively own unfinished release work that prevents another caller from completing it.
10. Invalid amounts do not change state; insufficient budget does not cause waiting for replenishment.

## Your review

- Invariant violated: TODO
- Exact operation history: TODO
- Expected / observed result: TODO
- Proposed fix and reasoning: TODO
- Regression test: TODO
