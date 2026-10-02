package org.example.retry;

import java.time.Duration;

/** Attempt deadline visible to transport adapters on the attempt's thread. */
public final class RetryAttempt {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private RetryAttempt() {}

    static void start(Duration timeout) { DEADLINE.set(System.nanoTime() + timeout.toNanos()); }
    static void clear() { DEADLINE.remove(); }

    public static Duration timeoutWithin(Duration configured) {
        Long deadline = DEADLINE.get();
        if (deadline == null) return configured;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new RetryExecutionException("Attempt deadline expired", null);
        return Duration.ofNanos(Math.min(configured.toNanos(), remaining));
    }
}
