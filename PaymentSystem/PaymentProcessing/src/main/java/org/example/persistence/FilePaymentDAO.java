package org.example.persistence;

import org.example.TransactionState;
import org.example.TransactionStatus;
import org.example.plugin.PaymentPluginStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Adapts the local snapshot store to the existing callbacks' DAO contract. Uses one configured mock provider. */
public final class FilePaymentDAO implements PaymentDAO {
    private final PaymentTransactionRepository transactions;
    private final String pluginName;

    public FilePaymentDAO(PaymentTransactionRepository transactions, String pluginName) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.pluginName = Objects.requireNonNull(pluginName, "pluginName");
        if (pluginName.isBlank()) {
            throw new IllegalArgumentException("A plugin name is required");
        }
    }

    @Override
    public PaymentTransactionModel insertPaymentTransaction(PaymentTransactionModel transaction) {
        transactions.createTransaction(transaction);
        return transaction;
    }

    @Override
    public int updatePaymentTransactionStatus(UUID accountId, UUID paymentId, UUID transactionId,
                                              TransactionStatus status, String gatewayTransactionId,
                                              String gatewayErrorCode, String gatewayError, Instant processedAt,
                                              PaymentPluginStatus gatewayStatus) {
        PaymentTransactionModel previous = transactions.findTransactionById(transactionId).orElse(null);
        if (previous == null || !previous.accountId().equals(accountId) || !previous.paymentId().equals(paymentId)) {
            return 0;
        }
        transactions.updateTransaction(new PaymentTransactionModel(previous.id(), previous.accountId(), previous.paymentId(),
                previous.transactionType(), previous.amount(), previous.currency(), status,
                gatewayTransactionId == null ? previous.gatewayTransactionId() : gatewayTransactionId,
                gatewayErrorCode, gatewayError, previous.createdAt(), processedAt,
                TransactionState.forOutcome(previous.transactionType(), status), gatewayStatus));
        return 1;
    }

    @Override
    public String getPaymentProviderPluginName(UUID accountId, UUID paymentMethodId, boolean includeDeletedPaymentMethod) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(paymentMethodId, "paymentMethodId");
        return pluginName;
    }
}
