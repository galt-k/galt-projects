package org.example.retry;

import org.example.ledger.LedgerException;
import org.example.ledger.LedgerResponse;
import org.example.ledger.LedgerStatus;
import org.example.plugin.AuthorizationAttemptException;
import org.example.plugin.PaymentPluginAPIException;
import org.example.plugin.PaymentPluginStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RetryClassifierTest {
    @ParameterizedTest
    @EnumSource(PaymentPluginStatus.class)
    void authorizationBusinessOutcomesNeverRetry(PaymentPluginStatus status) {
        assertEquals(status == PaymentPluginStatus.PENDING ? RetryDecision.Action.RECONCILE : RetryDecision.Action.COMPLETE,
                new AuthorizationRetryClassifier().classify(status, null).action());
    }

    @ParameterizedTest
    @EnumSource(LedgerStatus.class)
    void ledgerBusinessOutcomesNeverRetry(LedgerStatus status) {
        assertNotEquals(RetryDecision.Action.RETRY,
                new LedgerRetryClassifier().classify(new LedgerResponse(status, "entry", null, null), null).action());
    }

    @Test
    void unresolvedClassificationRequiresProviderPermissionAndIdempotency() {
        var classifier = new AuthorizationRetryClassifier();
        for (boolean safe : new boolean[]{true, false}) {
            for (var advice : AttemptOutcome.RetryAdvice.values()) {
                var facts = new AttemptOutcome(AttemptOutcome.Outcome.UNKNOWN, advice, safe, Duration.ofSeconds(2));
                var decision = classifier.classify(null, new AuthorizationAttemptException("test", null, facts));
                boolean retry = safe && advice == AttemptOutcome.RetryAdvice.PERMITTED;
                assertEquals(retry ? RetryDecision.Action.RETRY : RetryDecision.Action.RECONCILE, decision.action());
                if (retry) assertEquals(Duration.ofSeconds(2), decision.minimumDelay());
            }
        }
        assertEquals(RetryDecision.Action.RECONCILE, classifier.classify(null, new PaymentPluginAPIException("generic error")).action());
        assertEquals(RetryDecision.Action.RECONCILE, new LedgerRetryClassifier().classify(null, new LedgerException("unknown", null)).action());
    }

    @Test
    void forbiddenTransientFailureAndConflictNeverRetry() {
        var forbidden = new AttemptOutcome(AttemptOutcome.Outcome.TRANSIENT_FAILURE, AttemptOutcome.RetryAdvice.FORBIDDEN, true, Duration.ZERO);
        assertEquals(RetryDecision.Action.RECONCILE, RetryDecision.decide(forbidden).action());
        var conflict = new AttemptOutcome(AttemptOutcome.Outcome.IDEMPOTENCY_CONFLICT, AttemptOutcome.RetryAdvice.PERMITTED, true, Duration.ZERO);
        assertEquals(RetryDecision.Action.STOP, RetryDecision.decide(conflict).action());
    }

    @Test
    void parsesRetryAfterSecondsDatesAndInvalidGuidanceConservatively() {
        Instant now = Instant.parse("2026-10-02T12:00:00Z");
        assertEquals(Duration.ofSeconds(3), RetryAfter.parse("3", now));
        assertEquals(Duration.ofSeconds(5), RetryAfter.parse("Fri, 02 Oct 2026 12:00:05 GMT", now));
        assertEquals(Duration.ZERO, RetryAfter.parse("Fri, 02 Oct 2026 11:00:05 GMT", now));
        assertEquals(Duration.ofDays(1), RetryAfter.parse("99999999999999999999999999", now));
        assertEquals(Duration.ofDays(1), RetryAfter.parse("bad", now));
    }
}
