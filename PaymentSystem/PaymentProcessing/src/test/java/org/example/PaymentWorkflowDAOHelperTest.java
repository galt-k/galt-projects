package org.example;

import org.example.persistence.JdbcPaymentDAO;
import org.example.persistence.PaymentDAO;
import org.example.persistence.PaymentTransactionModel;
import org.example.persistence.PaymentWorkflowDAOHelper;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class PaymentWorkflowDAOHelperTest {
    public static void main(String[] args) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:payment_dao;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/payment-schema.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE outer_work (id INTEGER PRIMARY KEY)");
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);
        PaymentDAO dao = new JdbcPaymentDAO(dataSource);
        Instant createdAt = Instant.parse("2026-09-24T12:00:00Z");
        Instant processedAt = createdAt.plusSeconds(60);
        PaymentWorkflowDAOHelper helper = new PaymentWorkflowDAOHelper(dao, manager, Clock.fixed(createdAt, ZoneOffset.UTC));
        PaymentWorkflowDAOHelper updater = new PaymentWorkflowDAOHelper(dao, manager, Clock.fixed(processedAt, ZoneOffset.UTC));
        UUID accountId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Currency currency = Currency.getInstance("USD");
        BigDecimal amount = new BigDecimal("123.456789");
        TransactionTemplate outer = new TransactionTemplate(manager);
        AtomicReference<PaymentTransactionModel> inserted = new AtomicReference<>();

        outer.executeWithoutResult(outerStatus -> {
            jdbc.update("INSERT INTO outer_work VALUES (1)");
            try {
                inserted.set(helper.insertPaymentTransaction(accountId, paymentId, TransactionType.CAPTURE,
                        amount, currency, TransactionStatus.UNKNOWN));
            } catch (PaymentAPIException exception) {
                throw new AssertionError(exception);
            }
            outerStatus.setRollbackOnly();
        });
        PaymentTransactionModel transaction = inserted.get();
        check(jdbc.queryForObject("SELECT COUNT(*) FROM outer_work", Integer.class) == 0, "Outer work must roll back");
        check(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transactions", Integer.class) == 1, "Intent must survive outer rollback");
        check(transaction.accountId().equals(accountId) && transaction.paymentId().equals(paymentId), "Stored identifiers must round-trip");
        check(transaction.amount().compareTo(amount) == 0 && transaction.currency().equals(currency), "Money must round-trip without loss");
        check(transaction.status() == TransactionStatus.UNKNOWN && transaction.createdAt().equals(createdAt)
                && transaction.processedAt() == null, "Intent must start UNKNOWN and unprocessed");

        outer.executeWithoutResult(outerStatus -> {
            jdbc.update("INSERT INTO outer_work VALUES (2)");
            try {
                updater.updatePaymentTransactionStatus(accountId, paymentId, transaction.id(),
                        TransactionStatus.SUCCESS, "gateway-123", "00", "approved");
            } catch (PaymentAPIException exception) {
                throw new AssertionError(exception);
            }
            outerStatus.setRollbackOnly();
        });
        check(value(jdbc, "status", transaction.id()).equals("SUCCESS"), "Outcome must survive outer rollback");
        check(value(jdbc, "gateway_transaction_id", transaction.id()).equals("gateway-123"), "Gateway transaction must persist");
        check(value(jdbc, "gateway_error_code", transaction.id()).equals("00"), "Gateway code must persist");
        check(value(jdbc, "gateway_error", transaction.id()).equals("approved"), "Gateway message must persist");
        check(jdbc.queryForObject("SELECT processed_at FROM payment_transactions WHERE id = ?",
                OffsetDateTime.class, transaction.id()).toInstant().equals(processedAt), "Processed timestamp must persist in UTC");

        updater.updatePaymentTransactionStatus(accountId, paymentId, transaction.id(), TransactionStatus.UNKNOWN,
                "awaiting settlement", "P01");
        check(value(jdbc, "status", transaction.id()).equals("UNKNOWN"), "Short update signature must set status");
        check(value(jdbc, "gateway_transaction_id", transaction.id()).equals("gateway-123"), "Short update must preserve gateway transaction ID");
        check(value(jdbc, "gateway_error", transaction.id()).equals("awaiting settlement")
                && value(jdbc, "gateway_error_code", transaction.id()).equals("P01"), "Error and code argument order must be correct");

        expectFailure(() -> updater.updatePaymentTransactionStatus(UUID.randomUUID(), paymentId, transaction.id(),
                TransactionStatus.SUCCESS, null, null));
        expectFailure(() -> updater.updatePaymentTransactionStatus(accountId, UUID.randomUUID(), transaction.id(),
                TransactionStatus.SUCCESS, null, null));
        expectFailure(() -> updater.updatePaymentTransactionStatus(accountId, paymentId, UUID.randomUUID(),
                TransactionStatus.SUCCESS, null, null));
        check(value(jdbc, "status", transaction.id()).equals("UNKNOWN"), "Mismatched identifiers must never update another record");
        expectFailure(() -> helper.insertPaymentTransaction(accountId, paymentId, TransactionType.CAPTURE,
                amount, currency, TransactionStatus.SUCCESS));

        DataIntegrityViolationException databaseFailure = new DataIntegrityViolationException("Failure after writing");
        AtomicReference<UUID> rolledBackId = new AtomicReference<>();
        PaymentDAO failingDAO = new PaymentDAO() {
            @Override
            public PaymentTransactionModel insertPaymentTransaction(PaymentTransactionModel model) {
                PaymentTransactionModel saved = dao.insertPaymentTransaction(model);
                rolledBackId.set(saved.id());
                throw databaseFailure;
            }

            @Override
            public int updatePaymentTransactionStatus(UUID account, UUID payment, UUID id, TransactionStatus status,
                    String gatewayId, String code, String error, Instant timestamp, org.example.plugin.PaymentPluginStatus gatewayStatus) {
                dao.updatePaymentTransactionStatus(account, payment, id, status, gatewayId, code, error, timestamp, gatewayStatus);
                throw databaseFailure;
            }

            @Override
            public String getPaymentProviderPluginName(UUID account, UUID method, boolean includeDeleted) {
                return dao.getPaymentProviderPluginName(account, method, includeDeleted);
            }
        };
        PaymentWorkflowDAOHelper failingHelper = new PaymentWorkflowDAOHelper(failingDAO, manager);
        PaymentAPIException insertFailure = expectFailure(() -> failingHelper.insertPaymentTransaction(accountId, paymentId,
                TransactionType.CAPTURE, amount, currency, TransactionStatus.UNKNOWN));
        check(insertFailure.getCause() == databaseFailure, "Insert failure must preserve the database cause");
        check(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transactions WHERE id = ?", Integer.class, rolledBackId.get()) == 0,
                "A failed insert must roll back");
        PaymentAPIException updateFailure = expectFailure(() -> failingHelper.updatePaymentTransactionStatus(accountId,
                paymentId, transaction.id(), TransactionStatus.SUCCESS, "bad", "bad"));
        check(updateFailure.getCause() == databaseFailure, "Update failure must preserve the database cause");
        check(value(jdbc, "status", transaction.id()).equals("UNKNOWN")
                && value(jdbc, "gateway_error_code", transaction.id()).equals("P01"), "A failed update must roll back all its fields");

        PaymentAPIException sqlFailure = expectFailure(() -> helper.insertPaymentTransaction(accountId, paymentId,
                TransactionType.CAPTURE, new BigDecimal("1E50"), currency, TransactionStatus.UNKNOWN));
        check(sqlFailure.getCause() instanceof DataAccessException, "SQL errors must be translated to PaymentAPIException");

        TransactionSystemException commitFailure = new TransactionSystemException("Commit failed");
        PlatformTransactionManager failingCommit = new PlatformTransactionManager() {
            @Override
            public org.springframework.transaction.TransactionStatus getTransaction(TransactionDefinition definition) {
                check(definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW, "Must request REQUIRES_NEW");
                return manager.getTransaction(definition);
            }

            @Override
            public void commit(org.springframework.transaction.TransactionStatus status) {
                manager.rollback(status);
                throw commitFailure;
            }

            @Override
            public void rollback(org.springframework.transaction.TransactionStatus status) {
                manager.rollback(status);
            }
        };
        PaymentWorkflowDAOHelper commitHelper = new PaymentWorkflowDAOHelper(dao, failingCommit);
        check(expectFailure(() -> commitHelper.insertPaymentTransaction(accountId, paymentId, TransactionType.CAPTURE,
                amount, currency, TransactionStatus.UNKNOWN)).getCause() == commitFailure, "Commit failures must be translated");
        check(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transactions", Integer.class) == 1, "Failed writes must leave only the committed intent");

        UUID methodId = UUID.randomUUID();
        jdbc.update("INSERT INTO payment_methods (id, account_id, plugin_name) VALUES (?, ?, ?)", methodId, accountId, "gateway");
        check(helper.getPaymentProviderPluginName(accountId, methodId, false).equals("gateway"), "Provider lookup must resolve the payment method");
        jdbc.update("UPDATE payment_methods SET deleted = TRUE WHERE id = ?", methodId);
        expectFailure(() -> helper.getPaymentProviderPluginName(accountId, methodId, false));
        check(helper.getPaymentProviderPluginName(accountId, methodId, true).equals("gateway"), "Include-deleted must be honored");
        expectFailure(() -> helper.getPaymentProviderPluginName(UUID.randomUUID(), methodId, true));
        System.out.println("Passed DAO persistence, transaction isolation, rollback, error translation, and provider lookup checks (H2 PostgreSQL mode)");
    }

    private static String value(JdbcTemplate jdbc, String column, UUID id) {
        return jdbc.queryForObject("SELECT " + column + " FROM payment_transactions WHERE id = ?", String.class, id);
    }

    @FunctionalInterface
    private interface FailingAction {
        void run() throws PaymentAPIException;
    }

    private static PaymentAPIException expectFailure(FailingAction action) {
        try {
            action.run();
            throw new AssertionError("Expected PaymentAPIException");
        } catch (PaymentAPIException exception) {
            return exception;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
