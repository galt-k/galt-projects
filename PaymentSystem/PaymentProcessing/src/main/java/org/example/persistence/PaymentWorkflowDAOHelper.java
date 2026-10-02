package org.example.persistence;

import org.example.PaymentAPIException;
import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.plugin.PaymentPluginStatus;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionOperations;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public class PaymentWorkflowDAOHelper {
    private final PaymentDAO paymentDAO;
    private final TransactionOperations persistenceBoundary;
    private final Clock clock;

    public PaymentWorkflowDAOHelper(DataSource dataSource) {
        this(new JdbcPaymentDAO(dataSource), new JdbcTransactionManager(dataSource));
    }

    public PaymentWorkflowDAOHelper(PaymentDAO paymentDAO, PlatformTransactionManager transactionManager) {
        this(paymentDAO, transactionManager, Clock.systemUTC());
    }

    public PaymentWorkflowDAOHelper(PaymentDAO paymentDAO, PlatformTransactionManager transactionManager, Clock clock) {
        this.paymentDAO = Objects.requireNonNull(paymentDAO, "paymentDAO");
        this.clock = Objects.requireNonNull(clock, "clock");
        TransactionTemplate requiresNew = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.persistenceBoundary = requiresNew;
    }

    public PaymentWorkflowDAOHelper(PaymentTransactionRepository transactions, String pluginName, Clock clock) {
        this.paymentDAO = new FilePaymentDAO(transactions, pluginName);
        this.clock = Objects.requireNonNull(clock, "clock");
        // The file repository commits each write by replacing its snapshot before returning.
        this.persistenceBoundary = TransactionOperations.withoutTransaction();
    }

    public PaymentTransactionModel insertPaymentTransaction(UUID accountId, UUID paymentId,
            TransactionType transactionType, BigDecimal amount, Currency currency, TransactionStatus status)
            throws PaymentAPIException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(transactionType, "transactionType");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (status != TransactionStatus.UNKNOWN) {
            throw new PaymentAPIException("New payment transactions must start in UNKNOWN", null);
        }
        return persist("Unable to persist payment transaction intent", ignored -> {
            PaymentTransactionModel transaction = new PaymentTransactionModel(
                    UUID.randomUUID(), accountId, paymentId, transactionType, amount, currency,
                    TransactionStatus.UNKNOWN, null, null, null, clock.instant(), null);
            return Objects.requireNonNull(paymentDAO.insertPaymentTransaction(transaction), "persisted transaction");
        });
    }

    public void updatePaymentTransactionStatus(UUID accountId, UUID paymentId, UUID transactionId,
            TransactionStatus targetStatus, String gatewayError, String gatewayErrorCode) throws PaymentAPIException {
        updatePaymentTransactionStatus(accountId, paymentId, transactionId, targetStatus,
                null, gatewayErrorCode, gatewayError);
    }

    public Instant updatePaymentTransactionStatus(UUID accountId, UUID paymentId, UUID transactionId,
            TransactionStatus targetStatus, String gatewayTransactionId, String gatewayErrorCode,
            String gatewayError) throws PaymentAPIException {
        return updatePaymentTransactionStatus(accountId, paymentId, transactionId, targetStatus,
                gatewayTransactionId, gatewayErrorCode, gatewayError, null);
    }

    public Instant updatePaymentTransactionStatus(UUID accountId, UUID paymentId, UUID transactionId,
            TransactionStatus targetStatus, String gatewayTransactionId, String gatewayErrorCode,
            String gatewayError, PaymentPluginStatus gatewayStatus) throws PaymentAPIException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(targetStatus, "targetStatus");
        return persist("Unable to persist payment transaction outcome", ignored -> {
            Instant processedAt = clock.instant();
            int rows = paymentDAO.updatePaymentTransactionStatus(accountId, paymentId, transactionId,
                    targetStatus, gatewayTransactionId, gatewayErrorCode, gatewayError, processedAt, gatewayStatus);
            if (rows != 1) {
                throw new IncorrectUpdateSemanticsDataAccessException(
                        "Expected one payment transaction to update, but updated " + rows);
            }
            return processedAt;
        });
    }

    public String getPaymentProviderPluginName(UUID accountId, UUID paymentMethodId,
            Boolean includeDeletedPaymentMethod) throws PaymentAPIException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(paymentMethodId, "paymentMethodId");
        Objects.requireNonNull(includeDeletedPaymentMethod, "includeDeletedPaymentMethod");
        try {
            return paymentDAO.getPaymentProviderPluginName(accountId, paymentMethodId, includeDeletedPaymentMethod);
        } catch (RuntimeException exception) {
            throw new PaymentAPIException("Unable to resolve payment provider plugin", exception);
        }
    }

    private <T> T persist(String message, TransactionCallback<T> operation) throws PaymentAPIException {
        try {
            return persistenceBoundary.execute(operation);
        } catch (RuntimeException exception) {
            throw new PaymentAPIException(message, exception);
        }
    }
}
