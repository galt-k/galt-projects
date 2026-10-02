package org.example.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.Payment;
import org.example.PaymentStage;
import org.example.PaymentState;
import org.example.TransactionState;
import org.example.TransactionStatus;
import org.example.TransactionType;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Single-writer snapshot store for local restart testing. Close it before reopening the same file. */
public final class FilePaymentRepository implements PaymentRepository, PaymentTransactionRepository, AutoCloseable {
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private Map<UUID, PaymentRow> payments = new LinkedHashMap<>();
    private Map<String, UUID> idempotencyKeys = new LinkedHashMap<>();
    private Map<UUID, PaymentTransactionModel> transactions = new LinkedHashMap<>();
    private boolean closed;

    public FilePaymentRepository(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        FileChannel channel = null;
        try {
            Files.createDirectories(this.file.getParent());
            channel = FileChannel.open(this.file.resolveSibling(this.file.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock acquired = channel.tryLock();
            if (acquired == null) {
                throw new IOException("Payment file is already open by another writer");
            }
            this.lockChannel = channel;
            this.fileLock = acquired;
            load();
        } catch (IOException | RuntimeException exception) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            throw new PaymentStorageException("Unable to open payment store " + this.file, exception);
        }
    }

    @Override
    public synchronized void create(Payment payment) {
        ensureOpen();
        PaymentRow row = PaymentRow.from(payment);
        if (payments.containsKey(row.paymentId())) {
            throw new IllegalStateException("Payment already exists: " + row.paymentId());
        }
        if (row.idempotencyKey() != null && idempotencyKeys.containsKey(row.idempotencyKey())) {
            throw new IllegalStateException("Payment idempotency key already exists");
        }
        Map<UUID, PaymentRow> updated = new LinkedHashMap<>(payments);
        updated.put(row.paymentId(), row);
        persist(updated, transactions);
    }

    @Override
    public synchronized PaymentRegistration createOrFind(Payment payment) {
        ensureOpen();
        Objects.requireNonNull(payment, "payment");
        String key = Objects.requireNonNull(payment.getIdempotencyKey(), "idempotencyKey");
        UUID existingId = idempotencyKeys.get(key);
        if (existingId != null) {
            return new PaymentRegistration(false, payments.get(existingId).toPayment(),
                    findTransactionsByPaymentId(existingId));
        }
        create(payment);
        return new PaymentRegistration(true, payments.get(payment.getPaymentId()).toPayment(), List.of());
    }

    @Override
    public synchronized Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        ensureOpen();
        UUID id = idempotencyKeys.get(Objects.requireNonNull(idempotencyKey, "idempotencyKey"));
        return Optional.ofNullable(payments.get(id)).map(PaymentRow::toPayment);
    }

    @Override
    public synchronized Optional<Payment> findById(UUID paymentId) {
        ensureOpen();
        return Optional.ofNullable(payments.get(Objects.requireNonNull(paymentId, "paymentId")))
                .map(PaymentRow::toPayment);
    }

    @Override
    public synchronized List<Payment> findAll() {
        ensureOpen();
        return payments.values().stream().map(PaymentRow::toPayment).toList();
    }

    @Override
    public synchronized void update(Payment payment) {
        ensureOpen();
        persist(updatedPayments(payment), transactions);
    }

    @Override
    public synchronized void createTransaction(PaymentTransactionModel transaction) {
        ensureOpen();
        validateTransaction(transaction, payments);
        if (transactions.containsKey(transaction.id())) {
            throw new IllegalStateException("Transaction already exists: " + transaction.id());
        }
        Map<UUID, PaymentTransactionModel> updated = new LinkedHashMap<>(transactions);
        updated.put(transaction.id(), transaction);
        persist(payments, updated);
    }

    @Override
    public synchronized Optional<PaymentTransactionModel> findTransactionById(UUID transactionId) {
        ensureOpen();
        return Optional.ofNullable(transactions.get(Objects.requireNonNull(transactionId, "transactionId")));
    }

    @Override
    public synchronized List<PaymentTransactionModel> findTransactionsByPaymentId(UUID paymentId) {
        ensureOpen();
        Objects.requireNonNull(paymentId, "paymentId");
        return transactions.values().stream().filter(transaction -> transaction.paymentId().equals(paymentId)).toList();
    }

    @Override
    public synchronized void updateTransaction(PaymentTransactionModel transaction) {
        ensureOpen();
        persist(payments, updatedTransactions(transaction));
    }

    /** Commits both existing records in one snapshot, so restart cannot observe only half the update. */
    public synchronized void updatePaymentAndTransaction(Payment payment, PaymentTransactionModel transaction) {
        ensureOpen();
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(transaction, "transaction");
        if (!payment.getPaymentId().equals(transaction.paymentId())) {
            throw new IllegalArgumentException("Transaction must belong to the updated payment");
        }
        persist(updatedPayments(payment), updatedTransactions(transaction));
    }

    private Map<UUID, PaymentRow> updatedPayments(Payment payment) {
        PaymentRow row = PaymentRow.from(payment);
        PaymentRow previous = payments.get(row.paymentId());
        if (previous == null) {
            throw new NoSuchElementException("Payment does not exist: " + row.paymentId());
        }
        if (!previous.accountId().equals(row.accountId()) || !previous.paymentMethodId().equals(row.paymentMethodId())
                || !previous.amount().equals(row.amount()) || !previous.currency().equals(row.currency())
                || !Objects.equals(previous.idempotencyKey(), row.idempotencyKey())) {
            throw new IllegalArgumentException("Payment identity, method, amount, and currency cannot change");
        }
        Map<UUID, PaymentRow> updated = new LinkedHashMap<>(payments);
        updated.put(row.paymentId(), row);
        return updated;
    }

    private Map<UUID, PaymentTransactionModel> updatedTransactions(PaymentTransactionModel transaction) {
        validateTransaction(transaction, payments);
        PaymentTransactionModel previous = transactions.get(transaction.id());
        if (previous == null) {
            throw new NoSuchElementException("Transaction does not exist: " + transaction.id());
        }
        if (!previous.paymentId().equals(transaction.paymentId())
                || previous.transactionType() != transaction.transactionType()
                || !previous.amount().equals(transaction.amount())
                || !previous.createdAt().equals(transaction.createdAt())) {
            throw new IllegalArgumentException("Transaction identity, type, amount, and creation time cannot change");
        }
        Map<UUID, PaymentTransactionModel> updated = new LinkedHashMap<>(transactions);
        updated.put(transaction.id(), transaction);
        return updated;
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        JsonNode document = mapper.readTree(file.toFile());
        if (!(document instanceof ObjectNode root) || !root.path("version").isIntegralNumber()
                || !root.path("version").canConvertToInt()
                || !root.path("payments").isArray() || !root.path("transactions").isArray()) {
            throw new IOException("Invalid or unsupported payment snapshot");
        }
        if (root.path("version").intValue() == 1) {
            migrateLegacyTransactions(root);
        }
        Snapshot snapshot = mapper.treeToValue(root, Snapshot.class);
        if (snapshot.version() != 2 && snapshot.version() != 3) {
            throw new IOException("Unsupported payment snapshot version: " + snapshot.version());
        }
        for (PaymentRow row : snapshot.payments()) {
            row.toPayment();
            if (payments.putIfAbsent(row.paymentId(), row) != null) {
                throw new IOException("Duplicate payment ID in snapshot");
            }
        }
        for (PaymentTransactionModel transaction : snapshot.transactions()) {
            validateTransaction(transaction, payments);
            if (transactions.putIfAbsent(transaction.id(), transaction) != null) {
                throw new IOException("Duplicate transaction ID in snapshot");
            }
        }
        idempotencyKeys = indexKeys(payments);
    }

    private void persist(Map<UUID, PaymentRow> nextPayments, Map<UUID, PaymentTransactionModel> nextTransactions) {
        Map<String, UUID> nextKeys = indexKeys(nextPayments);
        Path temporary = null;
        try {
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                    new Snapshot(3, List.copyOf(nextPayments.values()), List.copyOf(nextTransactions.values())));
            temporary = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                output.force(true);
            }
            // Do not fall back to a non-atomic replacement when the filesystem cannot support this.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            payments = nextPayments;
            idempotencyKeys = nextKeys;
            transactions = nextTransactions;
        } catch (IOException exception) {
            throw new PaymentStorageException("Unable to save payment store " + file, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // An orphan temporary snapshot is never used during startup.
                }
            }
        }
    }

    private static Map<String, UUID> indexKeys(Map<UUID, PaymentRow> rows) {
        Map<String, UUID> keys = new LinkedHashMap<>();
        for (PaymentRow row : rows.values()) {
            if (row.idempotencyKey() != null && keys.putIfAbsent(row.idempotencyKey(), row.paymentId()) != null) {
                throw new IllegalArgumentException("Duplicate payment idempotency key in snapshot");
            }
        }
        return keys;
    }

    private static void validateTransaction(PaymentTransactionModel transaction, Map<UUID, PaymentRow> payments) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(transaction.id(), "transactionId");
        Objects.requireNonNull(transaction.transactionType(), "transactionType");
        Objects.requireNonNull(transaction.status(), "transactionStatus");
        Objects.requireNonNull(transaction.createdAt(), "createdAt");
        PaymentRow parent = payments.get(transaction.paymentId());
        if (parent == null || !parent.accountId().equals(transaction.accountId())
                || !parent.currency().equals(transaction.currency())) {
            throw new IllegalArgumentException("Transaction must reference an existing payment with the same account and currency");
        }
        if (transaction.amount() == null || transaction.amount().signum() <= 0) {
            throw new IllegalArgumentException("Transaction amount must be greater than zero");
        }
    }

    private static void migrateLegacyTransactions(ObjectNode root) throws IOException {
        for (JsonNode entry : root.path("transactions")) {
            if (!(entry instanceof ObjectNode transaction)) {
                throw new IOException("Invalid transaction in legacy snapshot");
            }
            String oldStatus = transaction.path("status").asText();
            TransactionStatus status = switch (oldStatus) {
                case "PAYMENT_FAILURE" -> TransactionStatus.FAILURE;
                case "PENDING", "UNKNOWN" -> TransactionStatus.UNKNOWN;
                case "SUCCESS" -> TransactionStatus.SUCCESS;
                default -> throw new IOException("Invalid legacy transaction status: " + oldStatus);
            };
            TransactionType type = TransactionType.valueOf(transaction.path("transactionType").asText());
            transaction.put("status", status.name());
            transaction.put("state", TransactionState.forOutcome(type, status).name());
            if (oldStatus.equals("PENDING")) {
                transaction.put("gatewayStatus", "PENDING");
            }
        }
        root.put("version", 2);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Payment store is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            try {
                try {
                    fileLock.release();
                } finally {
                    lockChannel.close();
                }
            } catch (IOException exception) {
                throw new PaymentStorageException("Unable to close payment store " + file, exception);
            }
        }
    }

    private record Snapshot(int version, List<PaymentRow> payments, List<PaymentTransactionModel> transactions) {}

    private record PaymentRow(UUID paymentId, UUID accountId, UUID paymentMethodId, BigDecimal amount,
                              Currency currency, PaymentState paymentState, PaymentStage paymentStage,
                              String idempotencyKey) {
        static PaymentRow from(Payment payment) {
            Objects.requireNonNull(payment, "payment");
            PaymentRow row = new PaymentRow(payment.getPaymentId(), payment.getAccountId(), payment.getPaymentMethodId(),
                    payment.getAmount(), payment.getCurrency(), payment.getPaymentState(), payment.getPaymentStage(),
                    payment.getIdempotencyKey());
            row.toPayment();
            return row;
        }

        Payment toPayment() {
            Payment payment = new Payment(paymentId, accountId, paymentMethodId, amount, currency, idempotencyKey);
            payment.setPaymentState(paymentState);
            payment.setPaymentStage(paymentStage);
            return payment;
        }
    }
}
