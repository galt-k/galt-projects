package dev.concurrencylab.batching;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Learning exercise: cancellation window-extension defect corrected; see review-exercise.md. */
public final class BatchScheduler {
    public static final int MAX_REQUESTS = 8;
    public static final int MAX_TOKEN_UNITS = 4_096;
    public static final int MAX_EXECUTION_SLOTS = 2;

    public enum AttemptState {
        RESERVED,
        HANDING_OFF
    }

    private static final long WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    public record Request(String id, int tokenUnits, Object payload) {
        public Request {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("A request ID is required");
            }
            if (tokenUnits <= 0 || tokenUnits > MAX_TOKEN_UNITS) {
                throw new IllegalArgumentException("Request must fit within the token limit");
            }
        }
    }

    public record Batch(List<Request> requests, int tokenUnits) {
        public Batch {
            requests = List.copyOf(requests);
            if (requests.isEmpty() || requests.size() > MAX_REQUESTS) {
                throw new IllegalArgumentException("Invalid batch size");
            }
            long total = requests.stream().mapToLong(Request::tokenUnits).sum();
            if (total != tokenUnits || total > MAX_TOKEN_UNITS) {
                throw new IllegalArgumentException("Invalid batch token total");
            }
        }
    }

    @FunctionalInterface
    public interface CancellationHandle {
        boolean cancel();
    }

    private static final class Identity {}
    private record Entry(Request request, long admittedAt) {}
    private record Candidate(int count, int tokenUnits, boolean limitReached) {}

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final LinkedHashMap<Identity, Entry> pending = new LinkedHashMap<>();
    private long windowOpenedAt;

    // Execution exercise: all fields below are guarded by the existing lock.
    // Presence means slot ownership; attempt IDs must never be reused.
    private final LinkedHashMap<Long, AttemptState> activeAttempts = new LinkedHashMap<>();
    private boolean shutdown;
    // Integrated dispatch owns the negative ID range; manual exercises use nonnegative IDs.
    private long nextDispatchAttemptId = Long.MIN_VALUE;

    /**
     * Reserve one execution slot for a fresh, never-reused attempt ID.
     * Called by the internal scheduler, not producer admission threads.
     *
     * Contract:
     * - Return true only after registering this attempt as RESERVED.
     * - Return false if shutdown prevents reservation; insert nothing.
     * - While full and still open, await changed and recheck after waking.
     * - Interruption during acquisition/wait propagates without registering ownership.
     * - An already-active ID is invalid: throw IllegalArgumentException without mutation.
     *
     * Invariants:
     * - Protect checks, waiting, and insertion with the existing shared lock.
     * - activeAttempts.size() never exceeds MAX_EXECUTION_SLOTS.
     * - Shutdown and reservation insertion make one coordinated decision.
     * - Never overwrite an existing attempt or reset HANDING_OFF to RESERVED.
     * - Release every successfully acquired lock on every exit path.
     *
     * No executor call or batch removal belongs in this method. Reservation and
     * completion-release integration will be connected in subsequent exercises.
     */
    public boolean reserveExecution(long attemptId) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            //duplicte attempts to be rejected
            if(activeAttempts.containsKey(attemptId)){
                throw new IllegalArgumentException("Attempt ID already owns an execution slot: " + attemptId);
            }

            //condition waiting loop
            while(activeAttempts.size()==MAX_EXECUTION_SLOTS && !shutdown){
                //go into waiting
                changed.await();
            }

            if(activeAttempts.containsKey(attemptId)){
                throw new IllegalArgumentException("Attempt ID already owns an execution slot: " + attemptId);
            }
            
            if(isShutdown()){
                return false;
            }

            //add the Id to activeattempts
            activeAttempts.put(attemptId, AttemptState.RESERVED);
            return true;

        }finally {
            lock.unlock();
        }
    }

    /** Commit RESERVED to HANDING_OFF, or reject when missing/committed/closed. */
    public boolean tryCommitHandoff(long attemptId) throws InterruptedException {
        lock.lockInterruptibly();
        try{
           //Check if Attempt is Missing?
           if(!activeAttempts.containsKey(attemptId)){
               return false;
           }
           //If the Attempt is Already "HadningOff?"
            if(activeAttempts.get(attemptId)== AttemptState.HANDING_OFF){
                return false;
            }
            //
            if(activeAttempts.get(attemptId)==AttemptState.RESERVED && shutdown){
                //Remove reservation
                activeAttempts.remove(attemptId);
                changed.signalAll();
                return false;
            }

            //change the state to handing off
            activeAttempts.put(attemptId, AttemptState.HANDING_OFF);
            return true;
        } finally {
            lock.unlock();
        }
        //throw new UnsupportedOperationException("Implement handoff commitment");
    }

    /**
     * Reclaim a reservation when its scheduler path exits before handoff commits.
     * For example, tryCommitHandoff may throw InterruptedException after a
     * successful reservation. The caller must arrange cleanup on that exit path.
     *
     * Decision steps (implement under the existing shared lock):
     * - Missing attempt: no change, return false.
     * - HANDING_OFF: preserve ownership, return false.
     * - RESERVED: remove ownership, signal changed, return true.
     *
     * Invariants:
     * - State validation and removal form one atomic decision.
     * - Never reclaim a committed execution's slot.
     * - Repeated cleanup and races with shutdown return capacity at most once.
     * - Work after shutdown too; do not require an open component for cleanup.
     * - Preserve all other attempts and pending requests.
     * - Signal capacity waiters only after successful removal, while holding lock.
     * - Release every successfully acquired lock on all exit paths.
     * - Cleanup must remain possible when the calling thread is already interrupted;
     *   this method has no checked interruption contract.
     *
     * This method only ends slot ownership. It does not discard/requeue a detached
     * batch, submit to the executor, or stop execution. Batch disposition belongs
     * to the caller's later integration policy. Attempt IDs must never be reused.
     */
    public boolean abandonReservation(long attemptId) {
        // TODO: implement state-sensitive, idempotent cleanup using the shared lock.
        lock.lock();
        try{
            if(!activeAttempts.containsKey(attemptId)){
                return false;
            }
            if(activeAttempts.get(attemptId) == AttemptState.HANDING_OFF){
                return false;
            }
            activeAttempts.remove(attemptId);
            changed.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * End ownership for a committed execution attempt.
     * Called on definite executor rejection, normal/failed execution completion,
     * or confirmation that execution has stopped. A timeout or a cancellation
     * request alone is not evidence that execution stopped.
     *
     * Decision steps (implement under the existing shared lock):
     * 1. Inspect this attempt's current state.
     * 2. If missing, return false without mutation or notification.
     * 3. If RESERVED, return false and preserve it: it has not committed to execution.
     * 4. If HANDING_OFF, remove its ownership, notify capacity waiters, return true.
     * 5. Release every successfully acquired lock on all exit paths.
     *
     * Invariants:
     * - State validation and removal are one atomic decision.
     * - Only successful removal returns capacity; map size remains the slot count.
     * - Duplicate/late notifications never return capacity twice.
     * - Other attempts and pending requests remain unchanged.
     * - Cleanup still works after shutdown; shutdown is not a reason to skip it.
     * - Signal the existing changed condition after removal, while holding its lock.
     * - No executor calls, completion waits, or callbacks run inside this method.
     *
     * Attempt IDs must never be reused. RESERVED reclamation belongs to shutdown
     * for this slice; pre-handoff cancellation/abandonment is a later integration concern.
     * This cleanup method has no checked interruption contract: an already-interrupted
     * caller must still be able to return ownership safely.
     */
    public boolean releaseExecution(long attemptId) {

        lock.lock();
        try {
            AttemptState state = activeAttempts.get(attemptId);
            if (state != AttemptState.HANDING_OFF) {
                return false;
            }
            activeAttempts.remove(attemptId);
            changed.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Close admission, reclaim RESERVED attempts, preserve HANDING_OFF, wake waiters. */
    public void shutdown() {
        // TODO: implement idempotent shutdown under the shared lock.
        lock.lock();
        try{
            shutdown = true;
            //Remove every reserved attempt
            Iterator<Map.Entry<Long, AttemptState>> iterator = activeAttempts.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<Long, AttemptState> entry = iterator.next();
                if (entry.getValue() == AttemptState.RESERVED) {
                    iterator.remove(); // Safely removes the current entry
                }
            }
            changed.signalAll();
        } finally{
            lock.unlock();
        }
    }

    // Later integration: reservation creation and submit/takeBatch must honor
    // execution capacity and shutdown. Existing queue behavior is unchanged.

    public CancellationHandle submit(Request request) {
        Objects.requireNonNull(request, "A request is required");
        Identity identity = new Identity();
        CancellationHandle handle = () -> cancel(identity);
        lock.lock();
        try {
            long now = System.nanoTime();
            if (pending.isEmpty()) {
                windowOpenedAt = now;
            }
            pending.put(identity, new Entry(request, now));
            changed.signalAll();
            return handle;
        } finally {
            lock.unlock();
        }
    }

    private boolean cancel(Identity identity) {
        lock.lock();
        try {
            if (pending.isEmpty()) {
                return false;
            }
            if (pending.remove(identity) == null) {
                return false;
            }
            if (pending.isEmpty()) {
                windowOpenedAt = 0;
            }
            changed.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Single scheduler caller. The returned requests are already handed off. */
    public Batch takeBatch() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                if (pending.isEmpty()) {
                    changed.await();
                    continue;
                }
                Candidate candidate = inspectPrefix();
                long elapsed = System.nanoTime() - windowOpenedAt;
                long remaining = WINDOW_NANOS - elapsed;
                if (candidate.limitReached() || remaining <= 0) {
                    return detach(candidate);
                }
                changed.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Caller holds lock; examines a bounded FIFO prefix. */
    private Candidate inspectPrefix() {
        int count = 0;
        int tokens = 0;
        for (Entry entry : pending.values()) {
            if (tokens + entry.request().tokenUnits() > MAX_TOKEN_UNITS) {
                return new Candidate(count, tokens, true);
            }
            count++;
            tokens += entry.request().tokenUnits();
            if (count == MAX_REQUESTS || tokens == MAX_TOKEN_UNITS) {
                return new Candidate(count, tokens, true);
            }
        }
        return new Candidate(count, tokens, false);
    }

    /** Caller holds lock; selection and removal form the handoff decision. */
    private Batch detach(Candidate candidate) {
        List<Request> requests = new ArrayList<>(candidate.count());
        Iterator<Entry> entries = pending.values().iterator();
        for (int i = 0; i < candidate.count(); i++) {
            requests.add(entries.next().request());
            entries.remove();
        }
        windowOpenedAt = pending.isEmpty()
                ? 0 : pending.firstEntry().getValue().admittedAt();
        return new Batch(requests, candidate.tokenUnits());
    }

    public boolean isShutdown(){
        return shutdown;
    }

    public enum SubmissionResult { ACCEPTED, REJECTED }

    @FunctionalInterface
    public interface BatchExecutor {
        /**
         * ACCEPTED transfers execution responsibility; invoke completion only after
         * execution finishes or is confirmed stopped (including failed execution).
         * Completion may run inline, before this call returns, or on another thread.
         * REJECTED guarantees no execution and no later execution will occur.
         * Normal rejection must use REJECTED, not an exception or null result.
         */
        SubmissionResult submit(long attemptId, Batch batch, Runnable completion);
    }

    public sealed interface DispatchResult {
        record Accepted(long attemptId, Batch batch) implements DispatchResult {}
        // Caller now owns this rejected batch; no automatic requeue is performed.
        record Rejected(long attemptId, Batch batch) implements DispatchResult {}
        record Stopped() implements DispatchResult {}
    }

    /** Carries detached work when an executor violates the explicit result contract. */
    public static final class UncertainSubmissionException extends RuntimeException {
        private final long attemptId;
        private final Batch batch;

        private UncertainSubmissionException(long attemptId, Batch batch, Throwable cause) {
            super("Executor submission outcome is uncertain; ownership is retained until completion", cause);
            this.attemptId = attemptId;
            this.batch = batch;
        }

        public long attemptId() { return attemptId; }
        public Batch batch() { return batch; }
    }

    /**
     * Single scheduler caller: dispatch at most one batch, waiting if necessary.
     * Review exercise: contains one intentional correctness defect.
     * Do not mix this entry point with takeBatch() or manual negative attempt IDs.
     *
     * Expected invariants:
     * - Reinspect queue state after waits; cancellation can change pending work.
     * - Secure capacity and handoff before detaching, under the shared lock.
     * - Keep external executor code outside the lock; register before callbacks.
     * - Retain accepted ownership until completion; release definite rejection.
     * - Stop on shutdown without detaching uncommitted requests.
     * - Return rejected detached work explicitly, never silently lose it.
     *
     * Existing queued-request disposition and full termination remain out of scope.
     */
    public DispatchResult dispatchNextBatch(BatchExecutor executor) throws InterruptedException {
        Objects.requireNonNull(executor, "An executor is required");
        Batch batch;
        long attemptId;
        lock.lockInterruptibly();
        try {
            while (true) {
                if (shutdown) {
                    return new DispatchResult.Stopped();
                }
                if (pending.isEmpty()) {
                    changed.await();
                    continue;
                }
                Candidate candidate = inspectPrefix();
                long remaining = WINDOW_NANOS - (System.nanoTime() - windowOpenedAt);
                if (!candidate.limitReached() && remaining > 0) {
                    changed.awaitNanos(remaining);
                    continue;
                }
                if (activeAttempts.size() >= MAX_EXECUTION_SLOTS && !shutdown) {
                    changed.await();
                    continue;
                }
                if (shutdown) {
                    return new DispatchResult.Stopped();
                }
                if (nextDispatchAttemptId == 0) {
                    throw new IllegalStateException("Execution attempt identity range exhausted");
                }
                attemptId = nextDispatchAttemptId++;
                if (!reserveExecution(attemptId)) {
                    return new DispatchResult.Stopped();
                }
                try {
                    if (!tryCommitHandoff(attemptId)) {
                        return new DispatchResult.Stopped();
                    }
                    try {
                        batch = detach(candidate);
                    } catch (RuntimeException | Error failure) {
                        releaseExecution(attemptId);
                        throw failure;
                    }
                } finally {
                    abandonReservation(attemptId);
                }
                break;
            }
        } finally {
            lock.unlock();
        }

        SubmissionResult result;
        try {
            result = Objects.requireNonNull(
                    executor.submit(attemptId, batch, () -> releaseExecution(attemptId)),
                    "Executor must return ACCEPTED or REJECTED");
        } catch (RuntimeException failure) {
            // An unknown outcome is not proof that execution stopped.
            throw new UncertainSubmissionException(attemptId, batch, failure);
        }
        if (result == SubmissionResult.REJECTED) {
            releaseExecution(attemptId);
            return new DispatchResult.Rejected(attemptId, batch);
        }
        return new DispatchResult.Accepted(attemptId, batch);
    }



}
