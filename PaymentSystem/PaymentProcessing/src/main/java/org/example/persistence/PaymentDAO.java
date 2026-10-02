package org.example.persistence;

import org.example.TransactionStatus;

import java.time.Instant;
import java.util.UUID;

public interface PaymentDAO {
    PaymentTransactionModel insertPaymentTransaction(PaymentTransactionModel transaction);

    int updatePaymentTransactionStatus(UUID accountId, UUID paymentId, UUID transactionId,
                                       TransactionStatus status, String gatewayTransactionId,
                                       String gatewayErrorCode, String gatewayError, Instant processedAt, org.example.plugin.PaymentPluginStatus gatewayStatus);

    String getPaymentProviderPluginName(UUID accountId, UUID paymentMethodId, boolean includeDeletedPaymentMethod);
}
