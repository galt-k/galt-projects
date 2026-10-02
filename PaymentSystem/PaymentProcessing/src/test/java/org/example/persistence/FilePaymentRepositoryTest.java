package org.example.persistence;

import org.example.Payment;
import org.example.PaymentStage;
import org.example.PaymentState;
import org.example.TransactionStatus;
import org.example.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Currency;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FilePaymentRepositoryTest {
    @TempDir
    Path directory;

    @Test
    void savesAndReloadsPaymentAndItsTransactions() {
        Path file = directory.resolve("payments.json");
        Payment payment = payment();
        PaymentTransactionModel authorization = transaction(payment, TransactionType.AUTHORIZE, TransactionStatus.SUCCESS);
        PaymentTransactionModel capture = transaction(payment, TransactionType.CAPTURE, TransactionStatus.UNKNOWN);
        try (FilePaymentRepository store = new FilePaymentRepository(file)) {
            store.create(payment);
            store.createTransaction(authorization);
            store.createTransaction(capture);
            payment.setPaymentStage(PaymentStage.CAPTURE);
            payment.setPaymentState(PaymentState.UNKNOWN);
            store.updatePaymentAndTransaction(payment, capture);
        }
        try (FilePaymentRepository store = new FilePaymentRepository(file)) {
            Payment restored = store.findById(payment.getPaymentId()).orElseThrow();
            assertEquals(payment.getAccountId(), restored.getAccountId());
            assertEquals(payment.getPaymentMethodId(), restored.getPaymentMethodId());
            assertEquals(payment.getAmount(), restored.getAmount());
            assertEquals(payment.getCurrency(), restored.getCurrency());
            assertEquals(PaymentStage.CAPTURE, restored.getPaymentStage());
            assertEquals(PaymentState.UNKNOWN, restored.getPaymentState());
            assertEquals(authorization, store.findTransactionById(authorization.id()).orElseThrow());
            assertEquals(capture, store.findTransactionById(capture.id()).orElseThrow());
            assertEquals(2, store.findTransactionsByPaymentId(payment.getPaymentId()).size());
        }
    }

    @Test
    void commitsOutcomeAndPaymentTogetherAndRejectsPartialValidation() {
        Path file = directory.resolve("payments.json");
        Payment payment = payment();
        PaymentTransactionModel intent = transaction(payment, TransactionType.CAPTURE, TransactionStatus.UNKNOWN);
        Instant processedAt = Instant.parse("2026-09-29T12:01:00Z");
        PaymentTransactionModel completed = new PaymentTransactionModel(intent.id(), intent.accountId(), intent.paymentId(),
                intent.transactionType(), intent.amount(), intent.currency(), TransactionStatus.SUCCESS,
                "capture-receipt", "00", null, intent.createdAt(), processedAt);
        try (FilePaymentRepository store = new FilePaymentRepository(file)) {
            store.create(payment);
            payment.setPaymentStage(PaymentStage.CAPTURE);
            payment.setPaymentState(PaymentState.SUCCEEDED);
            assertThrows(NoSuchElementException.class, () -> store.updatePaymentAndTransaction(payment, completed));
            assertEquals(PaymentState.PENDING, store.findById(payment.getPaymentId()).orElseThrow().getPaymentState());
            store.createTransaction(intent);
            store.updateTransaction(completed);
            assertEquals(completed, store.findTransactionById(intent.id()).orElseThrow());
            store.updatePaymentAndTransaction(payment, completed);
        }
        try (FilePaymentRepository store = new FilePaymentRepository(file)) {
            assertEquals(PaymentState.SUCCEEDED, store.findById(payment.getPaymentId()).orElseThrow().getPaymentState());
            assertEquals(completed, store.findTransactionById(intent.id()).orElseThrow());
        }
    }

    @Test
    void mutableObjectsCannotChangeStoredDataWithoutUpdate() {
        Payment payment = payment();
        try (FilePaymentRepository store = new FilePaymentRepository(directory.resolve("payments.json"))) {
            store.create(payment);
            payment.setPaymentState(PaymentState.SUCCEEDED);
            Payment read = store.findById(payment.getPaymentId()).orElseThrow();
            assertEquals(PaymentState.PENDING, read.getPaymentState());
            read.setPaymentState(PaymentState.FAILED);
            store.findAll().getFirst().setPaymentStage(PaymentStage.CAPTURE);
            assertEquals(PaymentState.PENDING, store.findById(payment.getPaymentId()).orElseThrow().getPaymentState());
            assertEquals(PaymentStage.CREATED, store.findById(payment.getPaymentId()).orElseThrow().getPaymentStage());
            store.update(read);
            read.setPaymentState(PaymentState.UNKNOWN);
            assertEquals(PaymentState.FAILED, store.findById(payment.getPaymentId()).orElseThrow().getPaymentState());
        }
    }

    @Test
    void rejectsDuplicateMissingAndUnrelatedRecords() {
        Payment payment = payment();
        PaymentTransactionModel transaction = transaction(payment, TransactionType.AUTHORIZE, TransactionStatus.UNKNOWN);
        try (FilePaymentRepository store = new FilePaymentRepository(directory.resolve("payments.json"))) {
            assertTrue(store.findById(payment.getPaymentId()).isEmpty());
            assertThrows(NoSuchElementException.class, () -> store.update(payment));
            assertThrows(IllegalArgumentException.class, () -> store.createTransaction(transaction));
            store.create(payment);
            assertThrows(IllegalStateException.class, () -> store.create(payment));
            assertThrows(NoSuchElementException.class, () -> store.updateTransaction(transaction));
            store.createTransaction(transaction);
            assertThrows(IllegalStateException.class, () -> store.createTransaction(transaction));
            Payment other = payment();
            assertThrows(IllegalArgumentException.class, () -> store.updatePaymentAndTransaction(other, transaction));
            Payment wrongAccount = new Payment(payment.getPaymentId(), UUID.randomUUID(), payment.getPaymentMethodId(),
                    payment.getAmount(), payment.getCurrency());
            assertThrows(IllegalArgumentException.class, () -> store.update(wrongAccount));
            assertEquals(1, store.findAll().size());
        }
    }

    @Test
    void failedReplacementDoesNotAdvanceMemoryOrOverwritePriorSnapshot() throws Exception {
        Path file = directory.resolve("payments.json");
        Path backup = directory.resolve("prior.json");
        Payment payment = payment();
        try (FilePaymentRepository store = new FilePaymentRepository(file)) {
            store.create(payment);
            byte[] original = Files.readAllBytes(file);
            Files.move(file, backup);
            Files.createDirectory(file);
            Files.writeString(file.resolve("blocker"), "prevent replacement");
            payment.setPaymentState(PaymentState.UNKNOWN);
            assertThrows(PaymentStorageException.class, () -> store.update(payment));
            assertEquals(PaymentState.PENDING, store.findById(payment.getPaymentId()).orElseThrow().getPaymentState());
            assertArrayEquals(original, Files.readAllBytes(backup));
            Files.delete(file.resolve("blocker"));
            Files.delete(file);
            Files.move(backup, file);
        }
        try (FilePaymentRepository store = new FilePaymentRepository(file)) {
            assertEquals(PaymentState.PENDING, store.findById(payment.getPaymentId()).orElseThrow().getPaymentState());
        }
    }

    @Test
    void refusesCorruptSnapshotWithoutResettingItAndReleasesLockOnFailure() throws Exception {
        Path file = directory.resolve("payments.json");
        for (String invalid : new String[]{"", "{", "null", "{\"version\":99,\"payments\":[],\"transactions\":[]}"}) {
            Files.writeString(file, invalid);
            assertThrows(PaymentStorageException.class, () -> new FilePaymentRepository(file));
            assertEquals(invalid, Files.readString(file));
        }
        Files.writeString(file, "{\"version\":1,\"payments\":[],\"transactions\":[]}");
        try (FilePaymentRepository store = new FilePaymentRepository(file)) {
            assertTrue(store.findAll().isEmpty());
        }
    }

    @Test
    void allowsOnlyOneWriterAndRejectsOperationsAfterClose() {
        Path file = directory.resolve("payments.json");
        FilePaymentRepository first = new FilePaymentRepository(file);
        try (first) {
            assertThrows(PaymentStorageException.class, () -> new FilePaymentRepository(file));
        }
        assertThrows(IllegalStateException.class, first::findAll);
        try (FilePaymentRepository reopened = new FilePaymentRepository(file)) {
            assertTrue(reopened.findAll().isEmpty());
        }
    }

    @Test
    void serializesConcurrentCreatesWithoutLosingRecords() throws Exception {
        Path file = directory.resolve("payments.json");
        try (FilePaymentRepository store = new FilePaymentRepository(file);
             var executor = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) {
                futures.add(executor.submit(() -> store.create(payment())));
            }
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            assertEquals(12, store.findAll().size());
        }
        try (FilePaymentRepository store = new FilePaymentRepository(file)) {
            assertEquals(12, store.findAll().size());
        }
    }

    @Test
    void reloadsAcknowledgedWritesAfterAbruptProcessExit() throws Exception {
        Path file = directory.resolve("payments.json");
        Path output = directory.resolve("writer.log");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                CrashWriter.class.getName(), file.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Writer did not exit");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        Files.writeString(directory.resolve("payments.json.orphan.tmp"), "unfinished snapshot");
        try (FilePaymentRepository store = new FilePaymentRepository(file)) {
            Payment restored = store.findAll().getFirst();
            assertEquals(PaymentState.UNKNOWN, restored.getPaymentState());
            assertEquals(PaymentStage.CAPTURE, restored.getPaymentStage());
            assertEquals(TransactionStatus.UNKNOWN,
                    store.findTransactionsByPaymentId(restored.getPaymentId()).getFirst().status());
        }
    }

    public static class CrashWriter {
        public static void main(String[] args) {
            FilePaymentRepository store = new FilePaymentRepository(Path.of(args[0]));
            Payment payment = payment();
            payment.setPaymentStage(PaymentStage.CAPTURE);
            payment.setPaymentState(PaymentState.UNKNOWN);
            store.create(payment);
            store.createTransaction(transaction(payment, TransactionType.CAPTURE, TransactionStatus.UNKNOWN));
            Runtime.getRuntime().halt(0);
        }
    }

    private static Payment payment() {
        return new Payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("123.456789012345678901"), Currency.getInstance("USD"));
    }

    private static PaymentTransactionModel transaction(Payment payment, TransactionType type, TransactionStatus status) {
        return new PaymentTransactionModel(UUID.randomUUID(), payment.getAccountId(), payment.getPaymentId(), type,
                payment.getAmount(), payment.getCurrency(), status, "gateway-id", null, null,
                Instant.parse("2026-09-29T12:00:00.123456Z"), null);
    }
}
