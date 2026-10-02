package org.example.plugin;

import org.example.CallContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MockAuthorizationIdempotencyTest {
    private final UUID account = UUID.randomUUID();
    private final UUID payment = UUID.randomUUID();
    private final UUID method = UUID.randomUUID();
    private final UUID transaction = UUID.randomUUID();
    private final Currency usd = Currency.getInstance("USD");

    @Test
    void concurrentAttemptsProduceOneHoldAndNewTransactionIsIndependent() throws Exception {
        var plugin = new MockAuthorizationPlugin(MockAuthorizationPlugin.Outcome.SUCCESS);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = new ArrayList<Future<PaymentPluginStatus>>();
            for (int i = 0; i < 20; i++) {
                attempts.add(executor.submit(() -> authorize(plugin, transaction)));
            }
            for (var attempt : attempts) assertEquals(PaymentPluginStatus.PROCESSED, attempt.get(5, TimeUnit.SECONDS));
        }
        assertEquals(20, plugin.authorizationCalls());
        assertEquals(1, plugin.authorizationOperations());
        assertEquals(1, plugin.holds());
        authorize(plugin, UUID.randomUUID());
        assertEquals(2, plugin.holds());
    }

    @ParameterizedTest
    @EnumSource(MockAuthorizationPlugin.Outcome.class)
    void replaysAllOutcomesWithoutCreatingAnotherOperation(MockAuthorizationPlugin.Outcome outcome) throws Exception {
        var plugin = new MockAuthorizationPlugin(outcome);
        if (outcome == MockAuthorizationPlugin.Outcome.UNKNOWN) {
            assertThrows(PaymentPluginAPIException.class, () -> authorize(plugin, transaction));
            assertThrows(PaymentPluginAPIException.class, () -> authorize(plugin, transaction));
        } else {
            var first = authorize(plugin, transaction);
            assertEquals(first, plugin.authorizePayment(account, payment, transaction, method,
                    new BigDecimal("10.0000"), usd, List.of(), new CallContext() {}));
        }
        assertEquals(1, plugin.authorizationOperations());
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "payment", "method", "amount", "currency", "properties"})
    void changedParametersDoNotCreateAnotherHold(String changed) throws Exception {
        var plugin = new MockAuthorizationPlugin(MockAuthorizationPlugin.Outcome.SUCCESS);
        authorize(plugin, transaction);
        assertThrows(PaymentPluginAPIException.class, () -> plugin.authorizePayment(
                changed.equals("account") ? UUID.randomUUID() : account,
                changed.equals("payment") ? UUID.randomUUID() : payment, transaction,
                changed.equals("method") ? UUID.randomUUID() : method,
                changed.equals("amount") ? BigDecimal.ONE : BigDecimal.TEN,
                changed.equals("currency") ? Currency.getInstance("EUR") : usd,
                changed.equals("properties") ? List.of(new PluginProperty("mode", "changed")) : List.of(), new CallContext() {}));
        assertEquals(1, plugin.holds());
        assertEquals(PaymentPluginStatus.PROCESSED, authorize(plugin, transaction));
    }

    private PaymentPluginStatus authorize(MockAuthorizationPlugin plugin, UUID id) throws PaymentPluginAPIException {
        return plugin.authorizePayment(account, payment, id, method, BigDecimal.TEN, usd, List.of(), new CallContext() {});
    }
}
