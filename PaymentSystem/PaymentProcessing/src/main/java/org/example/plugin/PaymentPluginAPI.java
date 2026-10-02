package org.example.plugin;

import org.example.CallContext;
import org.example.PaymentAPIException;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

// Gateway adapters implement this contract with their provider's network client.
public interface PaymentPluginAPI {
    PaymentPluginStatus checkoutPayment(UUID accountId, UUID paymentId, UUID transactionId,
                                       UUID paymentMethodId, BigDecimal amount, Currency currency,
                                       Iterable<PluginProperty> properties, CallContext callContext)
            throws PaymentPluginAPIException, PaymentAPIException;

    /**
     * transactionId is the durable idempotency key for this authorization. Adapters must pass it
     * to the provider's idempotency mechanism unchanged on every attempt; request tracing IDs
     * and the parent checkout key must not replace it.
     */
    PaymentPluginStatus authorizePayment(UUID accountId, UUID paymentId, UUID transactionId,
                                       UUID paymentMethodId, BigDecimal amount, Currency currency,
                                       Iterable<PluginProperty> properties, CallContext callContext)
            throws PaymentPluginAPIException, PaymentAPIException;

    PaymentPluginStatus capturePayment(UUID accountId, UUID paymentId, UUID transactionId,
                                       UUID paymentMethodId, BigDecimal amount, Currency currency,
                                       Iterable<PluginProperty> properties, CallContext callContext)
            throws PaymentPluginAPIException, PaymentAPIException;
}
