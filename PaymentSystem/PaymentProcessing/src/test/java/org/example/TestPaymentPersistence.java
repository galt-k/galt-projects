package org.example;

import org.example.persistence.PaymentDAO;
import org.example.persistence.PaymentTransactionModel;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.UUID;

final class TestPaymentPersistence {
    private TestPaymentPersistence() {}

    static PaymentDAO dao() {
        return new PaymentDAO() {
            @Override
            public PaymentTransactionModel insertPaymentTransaction(PaymentTransactionModel transaction) {
                throw new AssertionError("Unexpected DAO insert");
            }

            @Override
            public int updatePaymentTransactionStatus(UUID accountId, UUID paymentId, UUID transactionId,
                    org.example.TransactionStatus status, String gatewayTransactionId, String gatewayErrorCode,
                    String gatewayError, Instant processedAt, org.example.plugin.PaymentPluginStatus gatewayStatus) {
                throw new AssertionError("Unexpected DAO update");
            }

            @Override
            public String getPaymentProviderPluginName(UUID accountId, UUID paymentMethodId, boolean includeDeleted) {
                throw new AssertionError("Unexpected provider lookup");
            }
        };
    }

    static PlatformTransactionManager transactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                throw new AssertionError("Unexpected database transaction");
            }

            @Override
            public void commit(TransactionStatus status) {
                throw new AssertionError("Unexpected commit");
            }

            @Override
            public void rollback(TransactionStatus status) {
                throw new AssertionError("Unexpected rollback");
            }
        };
    }
}
