package org.example.plugin;

import org.example.retry.AttemptOutcome;
import java.util.Objects;

/** Provider adapters supply retry facts only after interpreting their provider's contract. */
public final class AuthorizationAttemptException extends PaymentPluginAPIException {
    private final AttemptOutcome attemptOutcome;

    public AuthorizationAttemptException(String message, Throwable cause, AttemptOutcome attemptOutcome) {
        super(message, cause);
        this.attemptOutcome = Objects.requireNonNull(attemptOutcome, "attemptOutcome");
    }

    public AttemptOutcome attemptOutcome() { return attemptOutcome; }
}
