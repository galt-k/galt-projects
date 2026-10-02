package org.killbill.billing.payment.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.CallContext;
import org.example.Payment;
import org.example.PaymentAPIException;
import org.example.PaymentStage;
import org.example.PaymentState;
import org.example.TransactionState;
import org.example.TransactionStatus;
import org.example.persistence.FilePaymentRepository;
import org.example.persistence.PaymentStorageException;
import org.example.persistence.PaymentTransactionModel;
import org.example.persistence.PaymentTransactionRepository;
import org.example.plugin.PaymentPluginAPI;
import org.example.plugin.PaymentPluginAPIException;
import org.example.plugin.PaymentPluginStatus;
import org.example.plugin.PluginProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.checkout.PaymentRepositoryConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationAutomatonRunnerTest {
    @TempDir
    Path directory;
    private FilePaymentRepository store;
    private Path file;
    private Payment parent;
    private int networkCalls;
    private final CallContext callContext = new CallContext() {};
    private final Instant now = Instant.parse("2026-09-29T12:00:00Z");

    @BeforeEach
    void setup() {
        file = directory.resolve("payments.json");
        store = new FilePaymentRepository(file);
        parent = new Payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("12.3400"), Currency.getInstance("USD"));
        parent.setPaymentStage(PaymentStage.AUTHORIZATION);
        store.create(parent);
    }

    @AfterEach
    void close() {
        store.close();
    }

    @ParameterizedTest
    @CsvSource({"PROCESSED,SUCCESS,AUTHORIZATION_COMPLETED", "ERROR,FAILURE,AUTHORIZATION_COMPLETED",
            "CANCELED,FAILURE,AUTHORIZATION_COMPLETED", "PENDING,UNKNOWN,AUTHORIZATION_REQUESTED",
            "TIMEOUT,UNKNOWN,AUTHORIZATION_REQUESTED", "API_ERROR,UNKNOWN,AUTHORIZATION_REQUESTED"})
    void runsActualCallbacksWithDurableIntentBeforeGateway(String provider, TransactionStatus expected,
                                                        TransactionState state) throws Exception {
        PaymentCoreApi api = api(store, plugin(() -> {
            return switch (provider) {
                case "TIMEOUT" -> throw new PaymentPluginAPIException("Gateway timed out");
                case "API_ERROR" -> throw new PaymentAPIException("Gateway response unavailable", null);
                default -> PaymentPluginStatus.valueOf(provider);
            };
        }));
        var result = authorize(api);
        assertEquals(1, networkCalls);
        assertEquals(expected, result.status());
        assertEquals(state, result.state());
        assertEquals(parent.getPaymentId(), result.paymentId());
        PaymentPluginStatus gatewayStatus = provider.equals("TIMEOUT") || provider.equals("API_ERROR")
                ? null : PaymentPluginStatus.valueOf(provider);
        assertEquals(gatewayStatus, result.gatewayStatus());
        var stored = store.findTransactionById(result.id()).orElseThrow();
        assertEquals(result, stored);
        assertEquals(now, stored.createdAt());
        assertEquals(now, stored.processedAt());
        if (gatewayStatus == null) {
            assertNotNull(stored.gatewayError());
        }
        store.close();
        store = new FilePaymentRepository(file);
        assertEquals(stored, store.findTransactionById(result.id()).orElseThrow());
        assertEquals(1, store.findTransactionsByPaymentId(parent.getPaymentId()).size());
    }

    @Test
    void springWiresCoreApiToActualAuthorizationExecutor() throws Exception {
        store.close();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("payment.store.path", file.toString())));
            context.registerBean(PaymentPluginAPI.class, () -> plugin(() -> PaymentPluginStatus.PROCESSED));
            context.register(PaymentRepositoryConfiguration.class, AuthorizationRunnerConfiguration.class, PaymentCoreApi.class);
            context.refresh();
            store = context.getBean(FilePaymentRepository.class);
            assertInstanceOf(AuthorizationAutomatonRunner.class, context.getBean(PaymentAutomatonRunner.class));
            assertEquals(TransactionStatus.SUCCESS, authorize(context.getBean(PaymentCoreApi.class)).status());
        }
        store = new FilePaymentRepository(file);
        assertEquals(TransactionStatus.SUCCESS, store.findTransactionsByPaymentId(parent.getPaymentId()).getFirst().status());
    }

    @Test
    void failedIntentPreventsGatewayCall() {
        PaymentTransactionRepository broken = new Transactions() {
            @Override
            public void createTransaction(PaymentTransactionModel transaction) {
                throw new PaymentStorageException("Intent write failed", null);
            }
        };
        assertThrows(PaymentApiException.class, () -> authorize(api(broken, plugin(() -> PaymentPluginStatus.PROCESSED))));
        assertEquals(0, networkCalls);
        assertTrue(store.findTransactionsByPaymentId(parent.getPaymentId()).isEmpty());
    }

    @Test
    void failedOutcomeDoesNotReturnSuccessAndLeavesSavedIntent() throws Exception {
        PaymentTransactionRepository broken = new Transactions() {
            @Override
            public void updateTransaction(PaymentTransactionModel transaction) {
                throw new PaymentStorageException("Outcome write failed", null);
            }
        };
        assertThrows(PaymentApiException.class, () -> authorize(api(broken, plugin(() -> PaymentPluginStatus.PROCESSED))));
        assertEquals(1, networkCalls);
        store.close();
        store = new FilePaymentRepository(file);
        var saved = store.findTransactionsByPaymentId(parent.getPaymentId()).getFirst();
        assertEquals(TransactionStatus.UNKNOWN, saved.status());
        assertEquals(TransactionState.AUTHORIZATION_REQUESTED, saved.state());
        assertNull(saved.processedAt());
        assertNull(saved.gatewayStatus());
    }

    @Test
    void invalidGatewayResponseRemainsUnknownAndIsReportedAsError() {
        assertThrows(PaymentApiException.class, () -> authorize(api(store, plugin(() -> null))));
        assertEquals(1, networkCalls);
        var saved = store.findTransactionsByPaymentId(parent.getPaymentId()).getFirst();
        assertEquals(TransactionStatus.UNKNOWN, saved.status());
        assertEquals(TransactionState.AUTHORIZATION_REQUESTED, saved.state());
        assertNotNull(saved.processedAt());
        assertTrue(saved.gatewayError().contains("no status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "account", "method", "amount", "currency", "stage", "state", "capture"})
    void rejectsInvalidRequestsBeforeIntentOrGateway(String mismatch) {
        PaymentCoreApi api = api(store, plugin(() -> PaymentPluginStatus.PROCESSED));
        if (mismatch.equals("stage")) {
            parent.setPaymentStage(PaymentStage.CAPTURE);
            store.update(parent);
        }
        if (mismatch.equals("state")) {
            parent.setPaymentState(PaymentState.UNKNOWN);
            store.update(parent);
        }
        assertThrows(PaymentApiException.class, () -> {
            if (mismatch.equals("capture")) {
                api.capture(parent::getAccountId, parent.getPaymentId(), parent.getPaymentMethodId(),
                        parent.getAmount(), parent.getCurrency(), callContext);
            } else {
                api.authorize(() -> mismatch.equals("account") ? UUID.randomUUID() : parent.getAccountId(),
                        mismatch.equals("missing") ? UUID.randomUUID() : parent.getPaymentId(),
                        mismatch.equals("method") ? UUID.randomUUID() : parent.getPaymentMethodId(),
                        mismatch.equals("amount") ? BigDecimal.ONE : parent.getAmount(),
                        mismatch.equals("currency") ? Currency.getInstance("EUR") : parent.getCurrency(), callContext);
            }
        });
        assertEquals(0, networkCalls);
        assertTrue(store.findTransactionsByPaymentId(parent.getPaymentId()).isEmpty());
    }

    private PaymentCoreApi api(PaymentTransactionRepository transactions, PaymentPluginAPI plugin) {
        return new PaymentCoreApi(new AuthorizationAutomatonRunner(store, transactions, plugin, "mock",
                Clock.fixed(now, ZoneOffset.UTC)));
    }

    private org.killbill.billing.payment.api.PaymentTransaction authorize(PaymentCoreApi api) throws PaymentApiException {
        return api.authorize(parent::getAccountId, parent.getPaymentId(), parent.getPaymentMethodId(),
                parent.getAmount(), parent.getCurrency(), callContext);
    }

    private PaymentPluginAPI plugin(Gateway gateway) {
        return new PaymentPluginAPI() {
            @Override
            public PaymentPluginStatus authorizePayment(UUID accountId, UUID paymentId, UUID transactionId,
                    UUID methodId, BigDecimal amount, Currency currency, Iterable<PluginProperty> properties,
                    CallContext context) throws PaymentPluginAPIException, PaymentAPIException {
                networkCalls++;
                assertEquals(parent.getAccountId(), accountId);
                assertEquals(parent.getPaymentId(), paymentId);
                assertEquals(parent.getPaymentMethodId(), methodId);
                assertEquals(parent.getAmount(), amount);
                assertEquals(parent.getCurrency(), currency);
                assertSame(callContext, context);
                var intent = store.findTransactionById(transactionId).orElseThrow();
                assertEquals(TransactionState.AUTHORIZATION_REQUESTED, intent.state());
                assertEquals(TransactionStatus.UNKNOWN, intent.status());
                assertNull(intent.processedAt());
                try {
                    var disk = new ObjectMapper().readTree(file.toFile()).path("transactions").get(0);
                    assertEquals(transactionId.toString(), disk.path("id").asText());
                    assertEquals("AUTHORIZATION_REQUESTED", disk.path("state").asText());
                    assertEquals("UNKNOWN", disk.path("status").asText());
                } catch (java.io.IOException exception) {
                    throw new AssertionError("Intent must be readable from disk before the gateway call", exception);
                }
                return gateway.call();
            }

            @Override
            public PaymentPluginStatus capturePayment(UUID accountId, UUID paymentId, UUID transactionId,
                    UUID methodId, BigDecimal amount, Currency currency, Iterable<PluginProperty> properties, CallContext context) {
                throw new AssertionError("Capture must not run in the authorization executor");
            }

            @Override
            public PaymentPluginStatus checkoutPayment(UUID accountId, UUID paymentId, UUID transactionId,
                    UUID methodId, BigDecimal amount, Currency currency, Iterable<PluginProperty> properties, CallContext context) {
                throw new AssertionError("Direct checkout must not run in the authorization executor");
            }
        };
    }

    @FunctionalInterface
    private interface Gateway {
        PaymentPluginStatus call() throws PaymentPluginAPIException, PaymentAPIException;
    }

    private class Transactions implements PaymentTransactionRepository {
        @Override
        public void createTransaction(PaymentTransactionModel transaction) {
            store.createTransaction(transaction);
        }

        @Override
        public Optional<PaymentTransactionModel> findTransactionById(UUID id) {
            return store.findTransactionById(id);
        }

        @Override
        public List<PaymentTransactionModel> findTransactionsByPaymentId(UUID id) {
            return store.findTransactionsByPaymentId(id);
        }

        @Override
        public void updateTransaction(PaymentTransactionModel transaction) {
            store.updateTransaction(transaction);
        }
    }
}
