package org.example.persistence;

import org.example.TransactionStatus;
import org.example.TransactionType;
import org.example.TransactionState;
import org.example.plugin.PaymentPluginStatus;
import org.killbill.billing.payment.api.PaymentTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

public record PaymentTransactionModel(UUID id,
                                      UUID accountId,
                                      UUID paymentId,
                                      TransactionType transactionType,
                                      BigDecimal amount,
                                      Currency currency,
                                      TransactionStatus status,
                                      String gatewayTransactionId,
                                      String gatewayErrorCode,
                                      String gatewayError,
                                      Instant createdAt,
                                      Instant processedAt,
                                      TransactionState state,
                                      PaymentPluginStatus gatewayStatus) implements PaymentTransaction {
    public PaymentTransactionModel {
        if (state != TransactionState.forOutcome(transactionType, status)) {
            throw new IllegalArgumentException("Transaction state must match its type and outcome");
        }
        if (gatewayStatus == PaymentPluginStatus.PENDING && status != TransactionStatus.UNKNOWN) {
            throw new IllegalArgumentException("A pending gateway response must remain unresolved");
        }
    }

    public PaymentTransactionModel(UUID id, UUID accountId, UUID paymentId, TransactionType transactionType,
                                   BigDecimal amount, Currency currency, TransactionStatus status,
                                   String gatewayTransactionId, String gatewayErrorCode, String gatewayError,
                                   Instant createdAt, Instant processedAt) {
        this(id, accountId, paymentId, transactionType, amount, currency, status, gatewayTransactionId,
                gatewayErrorCode, gatewayError, createdAt, processedAt,
                TransactionState.forOutcome(transactionType, status), null);
    }
}
