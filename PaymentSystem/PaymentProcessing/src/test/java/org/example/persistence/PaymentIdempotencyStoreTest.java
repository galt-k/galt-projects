package org.example.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.Payment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PaymentIdempotencyStoreTest {
    @TempDir Path directory;

    @Test
    void concurrentRegistrationHasOneWinnerAndSurvivesRestart() throws Exception {
        Path file = directory.resolve("payments.json");
        UUID winner;
        try (var store = new FilePaymentRepository(file); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = new ArrayList<Future<PaymentRegistration>>();
            for (int i = 0; i < 30; i++) {
                attempts.add(workers.submit(() -> store.createOrFind(payment("same-key"))));
            }
            int created = 0;
            winner = attempts.getFirst().get(10, TimeUnit.SECONDS).payment().getPaymentId();
            for (var attempt : attempts) {
                var result = attempt.get(10, TimeUnit.SECONDS);
                assertEquals(winner, result.payment().getPaymentId());
                if (result.created()) created++;
            }
            assertEquals(1, created);
            assertThrows(IllegalStateException.class, () -> store.create(payment("same-key")));
            var saved = store.findById(winner).orElseThrow();
            var changedKey = new Payment(winner, saved.getAccountId(), saved.getPaymentMethodId(),
                    saved.getAmount(), saved.getCurrency(), "another-key");
            assertThrows(IllegalArgumentException.class, () -> store.update(changedKey));
        }
        try (var store = new FilePaymentRepository(file)) {
            assertEquals(winner, store.findByIdempotencyKey("same-key").orElseThrow().getPaymentId());
            assertFalse(store.createOrFind(payment("same-key")).created());
            assertEquals(1, store.findAll().size());
        }
    }

    @Test
    void legacyPaymentsRemainReadableAndUnkeyed() throws Exception {
        Path file = directory.resolve("payments.json");
        var mapper = new ObjectMapper();
        try (var store = new FilePaymentRepository(file)) {
            store.create(payment(null));
        }
        var root = (ObjectNode) mapper.readTree(file.toFile());
        root.put("version", 2);
        ((ObjectNode) root.path("payments").get(0)).remove("idempotencyKey");
        mapper.writeValue(file.toFile(), root);
        try (var store = new FilePaymentRepository(file)) {
            assertNull(store.findAll().getFirst().getIdempotencyKey());
            assertTrue(store.createOrFind(payment("new-key")).created());
        }
        assertEquals(3, mapper.readTree(file.toFile()).path("version").asInt());
    }

    @Test
    void duplicateKeysInSnapshotFailInsteadOfChoosingAPayment() throws Exception {
        Path file = directory.resolve("payments.json");
        try (var store = new FilePaymentRepository(file)) {
            store.create(payment("first"));
            store.create(payment("second"));
        }
        var mapper = new ObjectMapper();
        var root = mapper.readTree(file.toFile());
        ((ObjectNode) root.path("payments").get(1)).put("idempotencyKey", "first");
        mapper.writeValue(file.toFile(), root);
        assertThrows(PaymentStorageException.class, () -> new FilePaymentRepository(file));
    }

    private Payment payment(String key) {
        return new Payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("12.34"), Currency.getInstance("USD"), key);
    }
}
