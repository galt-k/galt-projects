package org.example.plugin;

import org.example.CallContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.retry.AttemptOutcome;
import org.example.retry.AuthorizationRetryClassifier;
import org.example.retry.RetryableOperation;
import java.time.Duration;

public class MockAuthorizationPlugin implements PaymentPluginAPI {
    public enum Outcome { SUCCESS, FAILURE, PENDING, UNKNOWN }

    private final Outcome outcome;
    private final AtomicInteger calls = new AtomicInteger();
    private final Map<UUID, AuthorizationRequest> operations = new HashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private int transientFailures;

    public MockAuthorizationPlugin(Outcome outcome) {
        this(outcome, 0);
    }

    public MockAuthorizationPlugin(Outcome outcome, int transientFailures) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        if (transientFailures < 0) throw new IllegalArgumentException("Transient failures cannot be negative");
        this.transientFailures = transientFailures;
    }

    public int authorizationCalls() {
        return calls.get();
    }

    public synchronized int authorizationOperations() {
        return operations.size();
    }

    public synchronized int holds() {
        return outcome == Outcome.SUCCESS ? operations.size() : 0;
    }

    @Override
    @RetryableOperation(policy = "authorization", classifier = AuthorizationRetryClassifier.class)
    public synchronized PaymentPluginStatus authorizePayment(UUID accountId, UUID paymentId, UUID transactionId,
            UUID paymentMethodId, BigDecimal amount, Currency currency,
            Iterable<PluginProperty> properties, CallContext callContext) throws PaymentPluginAPIException {
        calls.incrementAndGet();
        AuthorizationRequest request;
        try {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(paymentMethodId, "paymentMethodId");
            Objects.requireNonNull(currency, "currency");
            if (amount == null || amount.signum() <= 0) {
                throw new IllegalArgumentException("Authorization amount must be positive");
            }
            var propertyList = new ArrayList<PluginProperty>();
            Objects.requireNonNull(properties, "properties").forEach(propertyList::add);
            request = new AuthorizationRequest(accountId, paymentId, paymentMethodId,
                    amount.stripTrailingZeros(), currency, mapper.valueToTree(propertyList));
        } catch (RuntimeException exception) {
            throw new PaymentPluginAPIException("Invalid mock authorization request", exception);
        }
        AuthorizationRequest existing = operations.get(transactionId);
        if (existing != null && !existing.equals(request)) {
            throw new PaymentPluginAPIException("Authorization idempotency key reused with different request details");
        }
        operations.putIfAbsent(transactionId, request);
        if (outcome == Outcome.SUCCESS && transientFailures > 0) {
            transientFailures--;
            throw new AuthorizationAttemptException("Mock lost authorization response after applying hold", null,
                    new AttemptOutcome(AttemptOutcome.Outcome.UNKNOWN, AttemptOutcome.RetryAdvice.PERMITTED, true, Duration.ZERO));
        }
        return switch (outcome) {
            case SUCCESS -> PaymentPluginStatus.PROCESSED;
            case FAILURE -> PaymentPluginStatus.ERROR;
            case PENDING -> PaymentPluginStatus.PENDING;
            case UNKNOWN -> throw new PaymentPluginAPIException("Mock authorization outcome is unknown");
        };
    }

    private record AuthorizationRequest(UUID accountId, UUID paymentId, UUID paymentMethodId,
                                        BigDecimal amount, Currency currency, JsonNode properties) {}

    @Override
    public PaymentPluginStatus capturePayment(UUID accountId, UUID paymentId, UUID transactionId,
            UUID paymentMethodId, BigDecimal amount, Currency currency,
            Iterable<PluginProperty> properties, CallContext callContext) throws PaymentPluginAPIException {
        throw new PaymentPluginAPIException("Capture must use the ledger client");
    }

    @Override
    public PaymentPluginStatus checkoutPayment(UUID accountId, UUID paymentId, UUID transactionId,
            UUID paymentMethodId, BigDecimal amount, Currency currency,
            Iterable<PluginProperty> properties, CallContext callContext) throws PaymentPluginAPIException {
        throw new PaymentPluginAPIException("Checkout must sequence authorization and capture");
    }
}
