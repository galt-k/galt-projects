package org.example.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RetryExecutorTest {
    private RetryPolicy policy(int attempts, Duration attemptTimeout, Duration budget) {
        return new RetryPolicy(attempts, attemptTimeout, budget, Duration.ofMillis(1), 2, Duration.ofMillis(10), RetryPolicy.Jitter.NONE);
    }
    private RetryDecision retry(Duration minimum) { return new RetryDecision(RetryDecision.Action.RETRY, minimum); }

    @Test
    void retriesUntilSuccessAndRetainsOriginalFailureOnExhaustion() throws Throwable {
        try (var executor = new DefaultRetryExecutor()) {
            var calls = new AtomicInteger();
            var original = new IllegalStateException("transient");
            RetryClassifier classifier = (result, error) -> error == null ? RetryDecision.stop(RetryDecision.Action.COMPLETE) : retry(Duration.ZERO);
            var config = policy(3, Duration.ofSeconds(1), Duration.ofSeconds(2));
            assertEquals("done", executor.execute("test", config, classifier, () -> {
                if (calls.incrementAndGet() < 3) throw original;
                return "done";
            }));
            assertEquals(3, calls.get());
            calls.set(0);
            assertSame(original, assertThrows(IllegalStateException.class, () -> executor.execute("test", config, classifier, () -> {
                calls.incrementAndGet();
                throw original;
            })));
            assertEquals(3, calls.get());
            assertEquals(6, executor.attempts());
            assertEquals(4, executor.retries());
        }
    }

    @Test
    void providerDelayIsRespectedAndNotShortenedToFitDeadline() throws Throwable {
        try (var executor = new DefaultRetryExecutor()) {
            var calls = new AtomicInteger();
            var original = new IllegalStateException("rate limit");
            var config = policy(3, Duration.ofSeconds(1), Duration.ofSeconds(2));
            var failedAt = new AtomicReference<Instant>();
            assertEquals("done", executor.execute("test", config,
                    (result, error) -> error == null ? RetryDecision.stop(RetryDecision.Action.COMPLETE) : retry(Duration.ofMillis(60)), () -> {
                if (calls.incrementAndGet() == 1) {
                    failedAt.set(Instant.now());
                    throw original;
                }
                assertTrue(Duration.between(failedAt.get(), Instant.now()).toMillis() >= 60);
                return "done";
            }));
            calls.set(0);
            assertSame(original, assertThrows(IllegalStateException.class, () -> executor.execute("test", config,
                    (result, error) -> retry(Duration.ofDays(1)), () -> { calls.incrementAndGet(); throw original; })));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void boundsSlowAttemptByRemainingBudgetAndDoesNotOverlapAnotherAttempt() throws Exception {
        try (var executor = new DefaultRetryExecutor()) {
            var calls = new AtomicInteger();
            var cancelled = new CountDownLatch(1);
            long start = System.nanoTime();
            assertThrows(RetryExecutionException.class, () -> executor.execute("test",
                    policy(3, Duration.ofSeconds(3), Duration.ofMillis(100)), (result, error) -> retry(Duration.ZERO), () -> {
                calls.incrementAndGet();
                assertTrue(RetryAttempt.timeoutWithin(Duration.ofSeconds(3)).compareTo(Duration.ofMillis(100)) <= 0);
                try { Thread.sleep(Duration.ofSeconds(5)); }
                finally { cancelled.countDown(); }
                return "late";
            }));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
            assertTrue(cancelled.await(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void interruptionStopsBackoffAndPreservesInterruptFlag() throws Exception {
        try (var executor = new DefaultRetryExecutor()) {
            var classified = new CountDownLatch(1);
            var preserved = new AtomicBoolean();
            var error = new AtomicReference<Throwable>();
            var calls = new AtomicInteger();
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    executor.execute("test", policy(3, Duration.ofSeconds(1), Duration.ofSeconds(10)), (result, failure) -> {
                        classified.countDown();
                        return retry(Duration.ofSeconds(5));
                    }, () -> { calls.incrementAndGet(); throw new IllegalStateException(); });
                } catch (Throwable failure) {
                    error.set(failure);
                    preserved.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                assertTrue(classified.await(2, TimeUnit.SECONDS));
            } finally {
                caller.interrupt();
                caller.join(2000);
            }
            assertFalse(caller.isAlive());
            assertInstanceOf(RetryExecutionException.class, error.get());
            assertTrue(preserved.get());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void fullJitterUsesGrowingCappedWindow() {
        var policy = new RetryPolicy(10, Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofMillis(100), 2,
                Duration.ofMillis(500), RetryPolicy.Jitter.FULL);
        assertEquals(Duration.ZERO, policy.delayAfter(1, 0));
        assertEquals(Duration.ofMillis(50), policy.delayAfter(1, .5));
        assertEquals(Duration.ofMillis(100), policy.delayAfter(2, .5));
        assertEquals(Duration.ofMillis(200), policy.delayAfter(3, .5));
        assertEquals(Duration.ofMillis(250), policy.delayAfter(20, .5));
    }
}
