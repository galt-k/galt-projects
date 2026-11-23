package dev.concurrencylab.batching;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Caller-side integration contract: timing out a wait does not signal execution
 * completion. No scheduler timeout API exists yet; reuse this scenario when one
 * is introduced. The fake backend finishes only when the test explicitly tells it to.
 */
@Timeout(5)
class CallerTimeoutTest {
    @Test
    void callerTimeoutRetainsSlotsUntilBackendActuallyCompletes() throws Exception {
        BatchScheduler scheduler = new BatchScheduler();
        List<Runnable> completions = new ArrayList<>();
        CompletableFuture<String> firstReply = new CompletableFuture<>();
        BatchScheduler.BatchExecutor backend = (id, batch, completed) -> {
            boolean first = completions.isEmpty();
            completions.add(() -> {
                completed.run();
                if (first) firstReply.complete("finished");
            });
            return BatchScheduler.SubmissionResult.ACCEPTED;
        };

        // Full-token batches are immediately ready; no batching-window sleeps.
        scheduler.submit(new BatchScheduler.Request("A", BatchScheduler.MAX_TOKEN_UNITS, "a"));
        var first = assertInstanceOf(BatchScheduler.DispatchResult.Accepted.class,
                scheduler.dispatchNextBatch(backend));
        scheduler.submit(new BatchScheduler.Request("B", BatchScheduler.MAX_TOKEN_UNITS, "b"));
        var second = assertInstanceOf(BatchScheduler.DispatchResult.Accepted.class,
                scheduler.dispatchNextBatch(backend));
        Map<Long, BatchScheduler.AttemptState> bothOwned = Map.of(
                first.attemptId(), BatchScheduler.AttemptState.HANDING_OFF,
                second.attemptId(), BatchScheduler.AttemptState.HANDING_OFF);
        assertEquals(bothOwned, ownership(scheduler));

        // Timeout is guaranteed: the fake backend cannot finish spontaneously.
        assertThrows(TimeoutException.class,
                () -> firstReply.get(20, TimeUnit.MILLISECONDS));
        assertFalse(firstReply.isDone(), "Caller timeout must not finish the backend reply");
        assertEquals(bothOwned, ownership(scheduler),
                "Both execution slots must remain owned after the caller stops waiting");

        // Now the backend really finishes A; only A's slot may be returned.
        completions.get(0).run();
        assertEquals("finished", firstReply.get(1, TimeUnit.SECONDS));
        assertEquals(Map.of(second.attemptId(), BatchScheduler.AttemptState.HANDING_OFF),
                ownership(scheduler));
        completions.get(0).run();
        assertEquals(Map.of(second.attemptId(), BatchScheduler.AttemptState.HANDING_OFF),
                ownership(scheduler), "Duplicate completion must not release B's slot");

        completions.get(1).run();
        assertTrue(ownership(scheduler).isEmpty(), "All completed work must return capacity");
    }

    // Observe protected state without adding a production-only-for-tests accessor.
    @SuppressWarnings("unchecked")
    private static Map<Long, BatchScheduler.AttemptState> ownership(BatchScheduler scheduler)
            throws Exception {
        Field lockField = BatchScheduler.class.getDeclaredField("lock");
        Field attemptsField = BatchScheduler.class.getDeclaredField("activeAttempts");
        lockField.setAccessible(true);
        attemptsField.setAccessible(true);
        ReentrantLock lock = (ReentrantLock) lockField.get(scheduler);
        lock.lock();
        try {
            return Map.copyOf((Map<Long, BatchScheduler.AttemptState>) attemptsField.get(scheduler));
        } finally {
            lock.unlock();
        }
    }
}
