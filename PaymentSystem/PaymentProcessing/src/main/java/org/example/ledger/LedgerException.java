package org.example.ledger;

import org.example.retry.AttemptOutcome;
import java.util.Objects;

public class LedgerException extends Exception {
    private final AttemptOutcome attemptOutcome;
    private final Integer httpStatus;

    public LedgerException(String message, Throwable cause) {
        this(message, cause, null, AttemptOutcome.observed(AttemptOutcome.Outcome.UNKNOWN));
    }

    public LedgerException(String message, Throwable cause, Integer httpStatus, AttemptOutcome attemptOutcome) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.attemptOutcome = Objects.requireNonNull(attemptOutcome, "attemptOutcome");
    }

    public AttemptOutcome attemptOutcome() { return attemptOutcome; }
    public Integer httpStatus() { return httpStatus; }
}
