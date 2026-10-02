package org.example.retry;

import org.example.ledger.LedgerException;
import org.example.ledger.LedgerResponse;

public final class LedgerRetryClassifier implements RetryClassifier {
    @Override
    public RetryDecision classify(Object result, Throwable failure) {
        if (failure != null) {
            return RetryDecision.decide(failure instanceof LedgerException known ? known.attemptOutcome()
                    : AttemptOutcome.observed(AttemptOutcome.Outcome.UNKNOWN));
        }
        if (!(result instanceof LedgerResponse response)) return RetryDecision.stop(RetryDecision.Action.RECONCILE);
        return RetryDecision.decide(AttemptOutcome.observed(switch (response.status()) {
            case SUCCESS -> AttemptOutcome.Outcome.APPROVED;
            case FAILURE -> AttemptOutcome.Outcome.DECLINED;
            case PENDING -> AttemptOutcome.Outcome.PENDING;
            case UNKNOWN -> AttemptOutcome.Outcome.UNKNOWN;
        }));
    }
}
