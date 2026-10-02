package org.example.retry;

import java.time.Duration;
import java.util.Objects;

public record AttemptOutcome(Outcome outcome, RetryAdvice retryAdvice,
                             boolean idempotencyProtectionValid, Duration minimumRetryDelay) {
    public enum Outcome { APPROVED, DECLINED, PENDING, INVALID_REQUEST, IDEMPOTENCY_CONFLICT, TRANSIENT_FAILURE, UNKNOWN }
    public enum RetryAdvice { PERMITTED, FORBIDDEN, UNSPECIFIED }

    public AttemptOutcome {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(retryAdvice, "retryAdvice");
        Objects.requireNonNull(minimumRetryDelay, "minimumRetryDelay");
        if (minimumRetryDelay.isNegative()) throw new IllegalArgumentException("Retry delay cannot be negative");
    }

    public static AttemptOutcome observed(Outcome outcome) {
        return new AttemptOutcome(outcome, RetryAdvice.UNSPECIFIED, false, Duration.ZERO);
    }
}
