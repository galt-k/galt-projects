package org.killbill.billing.payment.checkout;

import org.example.Payment;
import org.example.PaymentStage;
import org.example.PaymentState;
import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.persistence.FilePaymentRepository;
import org.example.persistence.PaymentTransactionModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.core.PaymentCoreApi;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IdempotentCheckoutTest {
    @TempDir Path directory;
    private final UUID account = UUID.randomUUID();
    private final UUID method = UUID.randomUUID();
    private final BigDecimal amount = new BigDecimal("10.00");
    private final Currency currency = Currency.getInstance("USD");
    private final String key = UUID.randomUUID().toString();

    @ParameterizedTest
    @EnumSource(value = TransactionType.class, names = {"AUTHORIZE", "CAPTURE"})
    void concurrentRetriesReturnPendingWithoutRepeatingOperations(TransactionType blocked) throws Exception {
        try (var store = new FilePaymentRepository(directory.resolve("payments.json"));
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var calls = new AtomicInteger();
            var service = new CheckoutService(new PaymentCoreApi(context -> {
                calls.incrementAndGet();
                var intent = new PaymentTransactionModel(UUID.randomUUID(), account, context.getPaymentId(),
                        context.getTransactionType(), amount, currency, TransactionStatus.UNKNOWN,
                        null, null, null, Instant.now(), null);
                store.createTransaction(intent);
                if (context.getTransactionType() == blocked) {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Test did not release operation");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
                var result = new PaymentTransactionModel(intent.id(), account, context.getPaymentId(),
                        context.getTransactionType(), amount, currency, TransactionStatus.SUCCESS,
                        null, null, null, intent.createdAt(), Instant.now());
                store.updateTransaction(result);
                context.setPaymentTransaction(result);
            }), store);
            Future<CheckoutResult> first = workers.submit(() -> checkout(service));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                var retries = new ArrayList<Future<CheckoutResult>>();
                for (int i = 0; i < 20; i++) {
                    retries.add(workers.submit(() -> checkout(service)));
                }
                UUID paymentId = store.findByIdempotencyKey(key).orElseThrow().getPaymentId();
                for (var retry : retries) {
                    var result = retry.get(5, TimeUnit.SECONDS);
                    assertEquals(paymentId, result.paymentId());
                    assertEquals(PaymentState.PENDING, result.status());
                    assertEquals(blocked == TransactionType.AUTHORIZE ? PaymentStage.AUTHORIZATION : PaymentStage.CAPTURE,
                            result.stage());
                    assertNotNull(result.authorizationTransactionId());
                    assertEquals(blocked == TransactionType.CAPTURE, result.captureTransactionId() != null);
                }
                assertEquals(blocked == TransactionType.AUTHORIZE ? 1 : 2, calls.get());
                assertEquals(1, store.findAll().size());
            } finally {
                release.countDown();
            }
            var completed = first.get(10, TimeUnit.SECONDS);
            assertEquals(PaymentState.SUCCEEDED, completed.status());
            assertEquals(completed, checkout(service));
            assertEquals(2, calls.get());
            assertEquals(2, store.findTransactionsByPaymentId(completed.paymentId()).size());
        }
    }

    @Test
    void crashAfterParentCreationReturnsSavedStateWithoutRestartingWork() throws Exception {
        Path file = directory.resolve("payments.json");
        UUID paymentId;
        try (var store = new FilePaymentRepository(file)) {
            var payment = new Payment(UUID.randomUUID(), account, method, amount, currency, key);
            store.createOrFind(payment);
            paymentId = payment.getPaymentId();
        }
        try (var store = new FilePaymentRepository(file)) {
            var service = new CheckoutService(new PaymentCoreApi(context -> fail("Duplicate restarted work")), store);
            var result = checkout(service);
            assertEquals(paymentId, result.paymentId());
            assertEquals(PaymentState.PENDING, result.status());
            assertEquals(PaymentStage.CREATED, result.stage());
            assertNull(result.authorizationTransactionId());
            assertNull(result.captureTransactionId());
        }
    }

    @Test
    void failedRegistrationLeavesNoKeyOrPaymentAndDoesNotCallRunner() throws Exception {
        Path file = directory.resolve("payments.json");
        try (var store = new FilePaymentRepository(file)) {
            Files.createDirectory(file);
            Files.writeString(file.resolve("blocker"), "prevent atomic replacement");
            var service = new CheckoutService(new PaymentCoreApi(context -> fail("Unpersisted request reached runner")), store);
            assertThrows(PaymentApiException.class, () -> checkout(service));
            assertTrue(store.findAll().isEmpty());
            assertTrue(store.findByIdempotencyKey(key).isEmpty());
            Files.delete(file.resolve("blocker"));
            Files.delete(file);
            assertTrue(store.createOrFind(new Payment(UUID.randomUUID(), account, method, amount, currency, key)).created());
        }
    }

    private CheckoutResult checkout(CheckoutService service) throws PaymentApiException {
        return service.checkout(() -> account, method, amount, currency, new CheckoutCallContext(UUID.randomUUID(), key));
    }
}
