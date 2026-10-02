package org.example.retry;

import org.example.plugin.AuthorizationAttemptException;
import org.example.plugin.PaymentPluginStatus;

public final class AuthorizationRetryClassifier implements RetryClassifier {
    @Override
    public RetryDecision classify(Object result, Throwable failure) {
        if (failure != null) {
            AttemptOutcome observation = failure instanceof AuthorizationAttemptException known
                    ? known.attemptOutcome() : AttemptOutcome.observed(AttemptOutcome.Outcome.UNKNOWN);
            return RetryDecision.decide(observation);
        }
        if (!(result instanceof PaymentPluginStatus status)) return RetryDecision.stop(RetryDecision.Action.RECONCILE);
        return RetryDecision.decide(AttemptOutcome.observed(switch (status) {
            case PROCESSED -> AttemptOutcome.Outcome.APPROVED;
            case ERROR, CANCELED -> AttemptOutcome.Outcome.DECLINED;
            case PENDING -> AttemptOutcome.Outcome.PENDING;
        }));
    }
}
