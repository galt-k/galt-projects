package org.example.retry;

import java.time.Duration;
import java.util.Objects;

public record RetryDecision(Action action, Duration minimumDelay) {
    public enum Action { COMPLETE, RETRY, RECONCILE, STOP }

    public RetryDecision {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(minimumDelay, "minimumDelay");
        if (minimumDelay.isNegative()) throw new IllegalArgumentException("Retry delay cannot be negative");
    }

    public static RetryDecision decide(AttemptOutcome attempt) {
        return switch (attempt.outcome()) {
            case APPROVED, DECLINED -> stop(Action.COMPLETE);
            case PENDING -> stop(Action.RECONCILE);
            case INVALID_REQUEST, IDEMPOTENCY_CONFLICT -> stop(Action.STOP);
            case TRANSIENT_FAILURE, UNKNOWN -> classifyUnresolved(attempt);
        };
    }

    private static RetryDecision classifyUnresolved(AttemptOutcome attempt) {
        if (!attempt.idempotencyProtectionValid() || attempt.retryAdvice() == AttemptOutcome.RetryAdvice.FORBIDDEN) {
            return stop(Action.RECONCILE);
        }
        if (attempt.retryAdvice() == AttemptOutcome.RetryAdvice.PERMITTED
                || attempt.outcome() == AttemptOutcome.Outcome.TRANSIENT_FAILURE) {
            return new RetryDecision(Action.RETRY, attempt.minimumRetryDelay());
        }
        return stop(Action.RECONCILE);
    }

    public static RetryDecision stop(Action action) {
        return new RetryDecision(action, Duration.ZERO);
    }
}
