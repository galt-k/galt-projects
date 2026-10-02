package org.example;

import org.example.plugin.PluginProperty;
import org.example.statemachine.PaymentStateContext;

import java.math.BigDecimal;
import java.time.Instant;
import org.example.persistence.PaymentTransactionModel;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

final class PaymentTestContexts {
    private PaymentTestContexts() {}

    static PaymentTransactionModel transaction(PaymentStateContext context) {
        return new PaymentTransactionModel(UUID.randomUUID(), context.getAccountId(), context.getPaymentId(),
                context.getTransactionType(), context.getAmount(), context.getCurrency(), TransactionStatus.UNKNOWN,
                null, null, null, Instant.now(), null);
    }

    static PaymentStateContext create(InternalCallContext internalCallContext, TransactionType transactionType) {
        PaymentStateContext context = new PaymentStateContext(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("125.50"),
                Currency.getInstance("USD"), transactionType, new CallContext() {});
        context.setPaymentId(UUID.randomUUID());
        context.setInternalCallContext(internalCallContext);
        context.setProperties(List.of(new PluginProperty("reference", "test-payment")));
        return context;
    }
}
