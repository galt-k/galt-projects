package org.killbill.billing.payment.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.CallContext;
import org.example.PaymentStage;
import org.example.PaymentState;
import org.example.TransactionState;
import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.ledger.*;
import org.example.persistence.*;
import org.example.plugin.PaymentPluginAPI;
import org.example.plugin.PaymentPluginStatus;
import org.example.plugin.PluginProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.checkout.CheckoutService;
import org.killbill.billing.payment.checkout.PaymentRepositoryConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CaptureLedgerIntegrationTest {
    @TempDir Path directory;
    private FilePaymentRepository store;
    private Path file;
    private final UUID accountId = UUID.randomUUID();
    private final UUID methodId = UUID.randomUUID();
    private final BigDecimal amount = new BigDecimal("45.6700");
    private final Currency currency = Currency.getInstance("USD");
    private final CallContext callContext = new CallContext() {};
    private int authorizations;

    @BeforeEach
    void open() {
        file = directory.resolve("payments.json");
        store = new FilePaymentRepository(file);
    }

    @AfterEach
    void close() {
        store.close();
    }

    @ParameterizedTest
    @EnumSource(LedgerStatus.class)
    void fullCheckoutPersistsCaptureIntentBeforeRestCallAndReloadsOutcome(LedgerStatus status) throws Exception {
        AtomicReference<Throwable> observationFailure = new AtomicReference<>();
        try (var server = new MockLedgerServer(0, status, request -> {
            try {
                var snapshot = new ObjectMapper().readTree(file.toFile());
                var capture = snapshot.path("transactions").get(1);
                assertEquals(request.transactionId().toString(), capture.path("id").asText());
                assertEquals("CAPTURE_REQUESTED", capture.path("state").asText());
                assertEquals("UNKNOWN", capture.path("status").asText());
                assertEquals("SUCCESS", snapshot.path("transactions").get(0).path("status").asText());
                assertEquals("CAPTURE", snapshot.path("payments").get(0).path("paymentStage").asText());
                assertEquals("PENDING", snapshot.path("payments").get(0).path("paymentState").asText());
            } catch (Throwable failure) {
                observationFailure.set(failure);
            }
        }, Duration.ZERO);
             var client = new RestLedgerClient(server.endpoint(), Duration.ofSeconds(2))) {
            var result = service(store, client).checkout(() -> accountId, methodId, amount, currency, callContext);
            assertNull(observationFailure.get(), () -> String.valueOf(observationFailure.get()));
            assertEquals(1, authorizations);
            assertEquals(1, server.requests().size());
            assertEquals(status == LedgerStatus.SUCCESS ? 1 : 0, server.entries().size());
            var request = server.requests().getFirst();
            assertEquals(result.captureTransactionId(), request.transactionId());
            assertEquals(result.paymentId(), request.paymentId());
            assertEquals(accountId, request.accountId());
            assertEquals(amount, request.amount());
            assertEquals(currency, request.currency());
            PaymentState expected = switch (status) {
                case SUCCESS -> PaymentState.SUCCEEDED;
                case FAILURE -> PaymentState.FAILED;
                case PENDING, UNKNOWN -> PaymentState.UNKNOWN;
            };
            assertEquals(expected, result.status());
            var transaction = store.findTransactionById(result.captureTransactionId()).orElseThrow();
            assertEquals(switch (status) {
                case SUCCESS -> TransactionStatus.SUCCESS;
                case FAILURE -> TransactionStatus.FAILURE;
                case PENDING, UNKNOWN -> TransactionStatus.UNKNOWN;
            }, transaction.status());
            assertEquals(expected == PaymentState.UNKNOWN ? TransactionState.CAPTURE_REQUESTED
                    : TransactionState.CAPTURE_COMPLETED, transaction.state());
            if (status == LedgerStatus.SUCCESS) {
                assertEquals("entry-" + result.captureTransactionId(), transaction.gatewayTransactionId());
            }
            if (status == LedgerStatus.PENDING) {
                assertEquals(PaymentPluginStatus.PENDING, transaction.gatewayStatus());
            }
            store.close();
            store = new FilePaymentRepository(file);
            assertEquals(transaction, store.findTransactionById(result.captureTransactionId()).orElseThrow());
            assertEquals(expected, store.findById(result.paymentId()).orElseThrow().getPaymentState());
            assertEquals(2, store.findTransactionsByPaymentId(result.paymentId()).size());
            assertEquals(TransactionStatus.SUCCESS, store.findTransactionById(result.authorizationTransactionId()).orElseThrow().status());
        }
    }

    @Test
    void timeoutAfterMockLedgerUpdateRemainsUnknownWithoutRetry() throws Exception {
        try (var server = new MockLedgerServer(0, LedgerStatus.SUCCESS, request -> {}, Duration.ofMillis(800));
             var client = new RestLedgerClient(server.endpoint(), Duration.ofMillis(300))) {
            var result = service(store, client).checkout(() -> accountId, methodId, amount, currency, callContext);
            assertEquals(PaymentState.UNKNOWN, result.status());
            assertEquals(1, server.requests().size());
            assertEquals(1, server.entries().size(), "Ledger may apply the update even when its response times out");
            assertEquals(TransactionState.CAPTURE_REQUESTED, store.findTransactionById(result.captureTransactionId()).orElseThrow().state());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"intent", "outcome"})
    void persistenceFailureStopsCaptureOrPreventsSuccess(String failurePoint) throws Exception {
        PaymentTransactionRepository failing = new Transactions() {
            @Override
            public void createTransaction(PaymentTransactionModel transaction) {
                if (failurePoint.equals("intent") && transaction.transactionType() == TransactionType.CAPTURE) {
                    throw new PaymentStorageException("Capture intent write failed", null);
                }
                super.createTransaction(transaction);
            }

            @Override
            public void updateTransaction(PaymentTransactionModel transaction) {
                if (failurePoint.equals("outcome") && transaction.transactionType() == TransactionType.CAPTURE) {
                    throw new PaymentStorageException("Capture outcome write failed", null);
                }
                super.updateTransaction(transaction);
            }
        };
        try (var server = new MockLedgerServer(0, LedgerStatus.SUCCESS);
             var client = new RestLedgerClient(server.endpoint(), Duration.ofSeconds(2))) {
            assertThrows(PaymentApiException.class,
                    () -> service(failing, client).checkout(() -> accountId, methodId, amount, currency, callContext));
            assertEquals(failurePoint.equals("intent") ? 0 : 1, server.requests().size());
            var payment = store.findAll().getFirst();
            assertEquals(PaymentState.UNKNOWN, payment.getPaymentState());
            assertEquals(PaymentStage.CAPTURE, payment.getPaymentStage());
            var attempts = store.findTransactionsByPaymentId(payment.getPaymentId());
            assertEquals(TransactionStatus.SUCCESS, attempts.getFirst().status());
            if (failurePoint.equals("outcome")) {
                assertEquals(TransactionStatus.UNKNOWN, attempts.getLast().status());
                assertEquals(TransactionState.CAPTURE_REQUESTED, attempts.getLast().state());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-authorization", "failed-authorization", "account", "method", "amount", "currency", "repeat"})
    void rejectsIneligibleCaptureWithoutLedgerCall(String invalid) throws Exception {
        var parent = new org.example.Payment(UUID.randomUUID(), accountId, methodId, amount, currency);
        parent.setPaymentStage(PaymentStage.CAPTURE);
        store.create(parent);
        if (!invalid.equals("missing-authorization")) {
            store.createTransaction(new PaymentTransactionModel(UUID.randomUUID(), accountId, parent.getPaymentId(),
                    TransactionType.AUTHORIZE, amount, currency, invalid.equals("failed-authorization")
                    ? TransactionStatus.FAILURE : TransactionStatus.SUCCESS, null, null, null, java.time.Instant.now(), null));
        }
        if (invalid.equals("repeat")) {
            store.createTransaction(new PaymentTransactionModel(UUID.randomUUID(), accountId, parent.getPaymentId(),
                    TransactionType.CAPTURE, amount, currency, TransactionStatus.UNKNOWN, null, null, null, java.time.Instant.now(), null));
        }
        LedgerClient ledger = request -> { throw new AssertionError("Invalid capture must not reach ledger"); };
        var api = new PaymentCoreApi(new DefaultPaymentAutomatonRunner(store, store, plugin(), ledger, "mock"));
        int before = store.findTransactionsByPaymentId(parent.getPaymentId()).size();
        assertThrows(PaymentApiException.class, () -> api.capture(
                () -> invalid.equals("account") ? UUID.randomUUID() : accountId, parent.getPaymentId(),
                invalid.equals("method") ? UUID.randomUUID() : methodId,
                invalid.equals("amount") ? BigDecimal.ONE : amount,
                invalid.equals("currency") ? Currency.getInstance("EUR") : currency, callContext));
        assertEquals(before, store.findTransactionsByPaymentId(parent.getPaymentId()).size());
    }

    @Test
    void mockLedgerProfileWiresCompleteCheckout() throws Exception {
        store.close();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("mock-ledger");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("payment.store.path", file.toString())));
            context.registerBean(PaymentPluginAPI.class, this::plugin);
            context.register(PaymentRepositoryConfiguration.class, AuthorizationRunnerConfiguration.class,
                    MockLedgerConfiguration.class, PaymentCoreApi.class, CheckoutService.class);
            context.refresh();
            store = context.getBean(FilePaymentRepository.class);
            assertInstanceOf(DefaultPaymentAutomatonRunner.class, context.getBean(PaymentAutomatonRunner.class));
            var result = context.getBean(CheckoutService.class).checkout(() -> accountId, methodId, amount, currency, callContext);
            assertEquals(PaymentState.SUCCEEDED, result.status());
            assertEquals(1, context.getBean(MockLedgerServer.class).entries().size());
        }
        store = new FilePaymentRepository(file);
        assertEquals(PaymentState.SUCCEEDED, store.findAll().getFirst().getPaymentState());
    }

    private CheckoutService service(PaymentTransactionRepository transactions, LedgerClient ledger) {
        return new CheckoutService(new PaymentCoreApi(new DefaultPaymentAutomatonRunner(store, transactions,
                plugin(), ledger, "mock")), store);
    }

    private PaymentPluginAPI plugin() {
        return new PaymentPluginAPI() {
            @Override
            public PaymentPluginStatus authorizePayment(UUID account, UUID payment, UUID transaction, UUID method,
                    BigDecimal value, Currency unit, Iterable<PluginProperty> properties, CallContext context) {
                authorizations++;
                assertEquals(TransactionState.AUTHORIZATION_REQUESTED, store.findTransactionById(transaction).orElseThrow().state());
                return PaymentPluginStatus.PROCESSED;
            }

            @Override
            public PaymentPluginStatus capturePayment(UUID account, UUID payment, UUID transaction, UUID method,
                    BigDecimal value, Currency unit, Iterable<PluginProperty> properties, CallContext context) {
                throw new AssertionError("Capture must use ledger REST, not the payment provider");
            }

            @Override
            public PaymentPluginStatus checkoutPayment(UUID account, UUID payment, UUID transaction, UUID method,
                    BigDecimal value, Currency unit, Iterable<PluginProperty> properties, CallContext context) {
                throw new AssertionError("Checkout must sequence authorization and capture");
            }
        };
    }

    private class Transactions implements PaymentTransactionRepository {
        public void createTransaction(PaymentTransactionModel value) { store.createTransaction(value); }
        public void updateTransaction(PaymentTransactionModel value) { store.updateTransaction(value); }
        public Optional<PaymentTransactionModel> findTransactionById(UUID id) { return store.findTransactionById(id); }
        public List<PaymentTransactionModel> findTransactionsByPaymentId(UUID id) { return store.findTransactionsByPaymentId(id); }
    }
}
