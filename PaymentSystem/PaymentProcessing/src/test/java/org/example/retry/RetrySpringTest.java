package org.example.retry;

import org.example.plugin.MockAuthorizationPlugin;
import org.example.plugin.PaymentPluginAPIException;
import org.example.plugin.PaymentPluginStatus;
import org.junit.jupiter.api.Test;
import org.killbill.billing.payment.checkout.CheckoutCallContext;
import org.killbill.billing.payment.core.RetryConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RetrySpringTest {
    @Test
    void annotationRetriesThroughProxyAndNamedOverrideBeatsDefaults() throws Exception {
        try (var context = context(Map.of("payment.retry.defaults.max-attempts", "1",
                "payment.retry.defaults.initial-delay", "1ms", "payment.retry.policies.authorization.max-attempts", "3"))) {
            context.registerBean(MockAuthorizationPlugin.class, () -> new MockAuthorizationPlugin(MockAuthorizationPlugin.Outcome.SUCCESS, 2));
            context.refresh();
            var plugin = context.getBean(MockAuthorizationPlugin.class);
            assertEquals(PaymentPluginStatus.PROCESSED, authorize(plugin));
            assertEquals(3, plugin.authorizationCalls());
            assertEquals(1, plugin.authorizationOperations());
            assertEquals(1, plugin.holds());
            var policies = context.getBean(RetryAnnotationPostProcessor.PolicyRegistry.class);
            assertEquals(1, policies.policy("capture-ledger").maxAttempts());
            assertEquals(Duration.ofMillis(1), policies.policy("authorization").initialDelay());
            assertEquals(2, context.getBean(DefaultRetryExecutor.class).retries());
        }
    }

    @Test
    void attemptsAreNotResetWhenErrorsPersist() {
        try (var context = context(Map.of("payment.retry.defaults.max-attempts", "2", "payment.retry.defaults.initial-delay", "1ms"))) {
            context.registerBean(MockAuthorizationPlugin.class, () -> new MockAuthorizationPlugin(MockAuthorizationPlugin.Outcome.SUCCESS, 10));
            context.refresh();
            var plugin = context.getBean(MockAuthorizationPlugin.class);
            assertThrows(PaymentPluginAPIException.class, () -> authorize(plugin));
            assertEquals(2, plugin.authorizationCalls());
            assertEquals(1, plugin.holds());
        }
    }

    @Test
    void invalidConfigurationFailsStartup() {
        for (var settings : List.of(Map.of("payment.retry.defaults.max-attempts", "0"),
                Map.of("payment.retry.defaults.attempt-timeout", "0s"),
                Map.of("payment.retry.defaults.multiplier", "NaN"),
                Map.of("payment.retry.defaults.initial-delay", "2s", "payment.retry.defaults.max-delay", "1s"))) {
            try (var context = context(settings)) { assertThrows(RuntimeException.class, context::refresh); }
        }
    }

    @Test
    void unknownAnnotationPolicyFailsStartup() {
        try (var context = context(Map.of())) {
            context.registerBean(UnknownPolicy.class);
            assertThrows(RuntimeException.class, context::refresh);
        }
    }

    public static class UnknownPolicy {
        @RetryableOperation(policy = "typo", classifier = AuthorizationRetryClassifier.class)
        public PaymentPluginStatus execute() { return PaymentPluginStatus.PROCESSED; }
    }

    private AnnotationConfigApplicationContext context(Map<String, String> settings) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", new java.util.HashMap<>(settings)));
        context.register(RetryConfiguration.class);
        return context;
    }

    private PaymentPluginStatus authorize(MockAuthorizationPlugin plugin) throws PaymentPluginAPIException {
        return plugin.authorizePayment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.TEN, Currency.getInstance("USD"), List.of(), new CheckoutCallContext(UUID.randomUUID(), "parent"));
    }
}
