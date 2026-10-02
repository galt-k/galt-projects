package org.example.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.Payment;
import org.example.TransactionState;
import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.plugin.PaymentPluginStatus;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransactionLifecycleMigrationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"PENDING,UNKNOWN,AUTHORIZATION_REQUESTED", "UNKNOWN,UNKNOWN,AUTHORIZATION_REQUESTED",
            "PAYMENT_FAILURE,FAILURE,AUTHORIZATION_COMPLETED", "SUCCESS,SUCCESS,AUTHORIZATION_COMPLETED"})
    void upgradesLegacyFileWithoutLosingOutcome(String legacy, TransactionStatus status, TransactionState state) throws Exception {
        Path file = directory.resolve("payments.json");
        Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN,
                Currency.getInstance("USD"));
        UUID transactionId = UUID.randomUUID();
        try (var repository = new FilePaymentRepository(file)) {
            repository.create(payment);
            repository.createTransaction(new PaymentTransactionModel(transactionId, payment.getAccountId(), payment.getPaymentId(),
                    TransactionType.AUTHORIZE, payment.getAmount(), payment.getCurrency(), TransactionStatus.UNKNOWN,
                    null, null, null, Instant.now(), null));
        }
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(file.toFile());
        root.put("version", 1);
        ObjectNode transaction = (ObjectNode) root.path("transactions").get(0);
        transaction.remove("state");
        transaction.remove("gatewayStatus");
        transaction.put("status", legacy);
        mapper.writeValue(file.toFile(), root);
        String original = Files.readString(file);
        try (var repository = new FilePaymentRepository(file)) {
            var migrated = repository.findTransactionById(transactionId).orElseThrow();
            assertEquals(state, migrated.state());
            assertEquals(status, migrated.status());
            assertEquals(legacy.equals("PENDING") ? PaymentPluginStatus.PENDING : null, migrated.gatewayStatus());
            assertEquals(original, Files.readString(file), "Loading alone must not overwrite the snapshot");
            repository.update(payment);
        }
        assertEquals(3, mapper.readTree(file.toFile()).path("version").asInt());
        try (var repository = new FilePaymentRepository(file)) {
            assertEquals(state, repository.findTransactionById(transactionId).orElseThrow().state());
        }
    }

    @Test
    void migratesLegacySqlRows() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE payment_transactions (transaction_type VARCHAR(32), status VARCHAR(32), "
                + "CONSTRAINT payment_transactions_status_check CHECK (status IN ('SUCCESS','PAYMENT_FAILURE','PENDING','UNKNOWN')))");
        jdbc.update("INSERT INTO payment_transactions VALUES ('AUTHORIZE','PENDING'),('AUTHORIZE','PAYMENT_FAILURE'),('CAPTURE','SUCCESS')");
        new ResourceDatabasePopulator(new ClassPathResource("db/migrations/V2__transaction_lifecycle.sql")).execute(source);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_transactions WHERE lifecycle_state = 'AUTHORIZATION_REQUESTED' "
                + "AND status = 'UNKNOWN' AND gateway_status = 'PENDING'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_transactions WHERE lifecycle_state = 'AUTHORIZATION_COMPLETED' "
                + "AND status = 'FAILURE'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_transactions WHERE lifecycle_state = 'CAPTURE_COMPLETED' "
                + "AND status = 'SUCCESS'", Integer.class));
    }

    @Test
    void rejectsCompletedUnknownTransactions() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentTransactionModel(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), TransactionType.AUTHORIZE, BigDecimal.TEN, Currency.getInstance("USD"),
                TransactionStatus.UNKNOWN, null, null, null, Instant.now(), null,
                TransactionState.AUTHORIZATION_COMPLETED, null));
    }
}
