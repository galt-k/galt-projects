package org.example.retry;

import java.time.Duration;
import java.util.Objects;

public record RetryPolicy(int maxAttempts, Duration attemptTimeout, Duration maxElapsedTime,
                          Duration initialDelay, double multiplier, Duration maxDelay, Jitter jitter) {
    public enum Jitter { NONE, FULL }

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 100) throw new IllegalArgumentException("max-attempts must be between 1 and 100");
        positive(attemptTimeout, "attempt-timeout");
        positive(maxElapsedTime, "max-elapsed-time");
        positive(initialDelay, "initial-delay");
        positive(maxDelay, "max-delay");
        if (!Double.isFinite(multiplier) || multiplier < 1) throw new IllegalArgumentException("multiplier must be finite and >= 1");
        if (maxDelay.compareTo(initialDelay) < 0) throw new IllegalArgumentException("max-delay must be >= initial-delay");
        Objects.requireNonNull(jitter, "jitter");
    }

    private static void positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(name + " must be positive and at most one hour");
        }
    }

    public Duration delayAfter(int completedAttempts, double randomFraction) {
        if (completedAttempts < 1 || !Double.isFinite(randomFraction) || randomFraction < 0 || randomFraction >= 1) throw new IllegalArgumentException("Invalid backoff input");
        double bound = Math.min(maxDelay.toNanos(), initialDelay.toNanos() * Math.pow(multiplier, completedAttempts - 1));
        return Duration.ofNanos((long) (jitter == Jitter.FULL ? bound * randomFraction : bound));
    }
}
