package org.example;

import org.example.persistence.PaymentTransactionModel;

import org.example.persistence.PaymentWorkflowDAOHelper;

import org.example.plugin.PluginProperty;

import org.example.statemachine.PaymentStateContext;

import org.example.plugin.PaymentPluginAPI;
import org.example.plugin.PaymentPluginAPIException;
import org.example.plugin.PaymentPluginException;
import org.example.plugin.PaymentPluginStatus;
import org.example.plugin.PaymentTransactionInfoPlugin;
import org.example.states.Created;
import org.example.workflow.OperationCallbackBase;
import org.example.workflow.OperationResult;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

public class OperationCallbackBaseTest {
    public static void main(String[] args) throws Exception {
        verify(PaymentPluginStatus.PROCESSED, null, OperationResult.SUCCESS);
        verify(PaymentPluginStatus.PENDING, null, OperationResult.PENDING);
        verify(PaymentPluginStatus.ERROR, null, OperationResult.FAILURE);
        verify(PaymentPluginStatus.CANCELED, null, OperationResult.FAILURE);
        verify(null, new PaymentPluginAPIException("Gateway unavailable"), OperationResult.EXCEPTION);
        verify(null, new PaymentAPIException("Payment error", null), OperationResult.EXCEPTION);
        verify(null, new OperationException("Unrecoverable error"), null);
        verify(null, null, null);
        System.out.println("Passed 8 plugin callback checks");
    }

    private static void verify(PaymentPluginStatus status, Exception failure, OperationResult expected)
            throws Exception {
        PaymentStateContext context = PaymentTestContexts.create(new InternalCallContext() {}, TransactionType.CAPTURE);
        context.setException(new PaymentPluginException("Previous attempt"));
        context.setPaymentInfoPlugin(new PaymentTransactionInfoPlugin("previous", "old-code", "old-error"));
        context.setPaymentTransaction(PaymentTestContexts.transaction(context));
        PaymentWorkflowDAOHelper daoHelper = new PaymentWorkflowDAOHelper(TestPaymentPersistence.dao(), TestPaymentPersistence.transactions()) {
            @Override
            public String getPaymentProviderPluginName(UUID accountId, UUID paymentMethodId, Boolean includeDeletedPaymentMethod) {
                throw new AssertionError("Unexpected provider lookup");
            }

            @Override
            public PaymentTransactionModel insertPaymentTransaction(UUID accountId, UUID paymentId,
                    TransactionType transactionType, BigDecimal amount, Currency currency, TransactionStatus transactionStatus) {
                throw new AssertionError("Operation callback must not insert the intent");
            }

            @Override
            public java.time.Instant updatePaymentTransactionStatus(UUID accountId, UUID paymentId, UUID transactionId,
                    TransactionStatus status, String gatewayTransactionId, String responseCode, String errorMessage, org.example.plugin.PaymentPluginStatus gatewayStatus) {
                throw new AssertionError("Operation callback must not persist the outcome");
            }
        };
        int[] pluginCalls = {0};
        PaymentPluginAPI plugin = new PaymentPluginAPI() {
            @Override
            public PaymentPluginStatus checkoutPayment(UUID accountId, UUID paymentId, UUID transactionId, UUID paymentMethodId,
                    BigDecimal amount, Currency currency, Iterable<PluginProperty> properties, CallContext callContext) {
                throw new AssertionError("Unexpected checkout");
            }

            @Override
            public PaymentPluginStatus authorizePayment(UUID accountId, UUID paymentId, UUID transactionId, UUID paymentMethodId,
                    BigDecimal amount, Currency currency, Iterable<PluginProperty> properties, CallContext callContext) {
                throw new AssertionError("Unexpected authorization");
            }

            @Override
            public PaymentPluginStatus capturePayment(UUID accountId, UUID paymentId, UUID transactionId, UUID paymentMethodId,
                    BigDecimal amount, Currency currency, Iterable<PluginProperty> properties, CallContext callContext) throws PaymentPluginAPIException {
                check(accountId.equals(context.getAccountId()) && paymentId.equals(context.getPaymentId()), "Wrong payment identifiers");
                pluginCalls[0]++;
                if (failure instanceof PaymentPluginAPIException pluginException) {
                    throw pluginException;
                }
                return status;
            }
        };
        OperationCallbackBase callback = new OperationCallbackBase(daoHelper, context, plugin) {
            @Override
            protected PaymentPluginStatus doOperationCallback(PaymentPluginAPI pluginApi, PaymentStateContext actualContext)
                    throws PaymentPluginException, PaymentAPIException, OperationException {
                check(pluginApi == plugin && actualContext == context, "Dependencies must reach the subclass");
                check(this.daoHelper == daoHelper, "DAO dependency must be available to the subclass");
                check(actualContext.getException() == null, "Previous attempt exception must be cleared");
                check(actualContext.getPaymentInfoPlugin() == null, "Previous gateway response must be cleared");
                if (failure instanceof PaymentAPIException apiException) {
                    throw apiException;
                }
                if (failure instanceof OperationException operationException) {
                    throw operationException;
                }
                return pluginApi.capturePayment(actualContext.getAccountId(), actualContext.getPaymentId(),
                        actualContext.getTransactionId(), actualContext.getPaymentMethodId(), actualContext.getAmount(),
                        actualContext.getCurrency(), actualContext.getProperties(), actualContext.getCallContext());
            }
        };

        try {
            OperationResult result = callback.doOperation(Created.INSTANCE);
            check(expected != null && result == expected, "Unexpected operation result: " + result);
            check(context.getException() == failure, "Context must hold the original failure or no exception");
        } catch (OperationException exception) {
            check(expected == null, "Recoverable errors must return EXCEPTION");
            check(context.getException() == exception, "Unrecoverable error must be stored before propagation");
            if (failure != null) {
                check(exception == failure, "Original unrecoverable error must propagate");
            } else {
                check(exception.getMessage().equals("Payment plugin returned no status"), "Null status must abort");
            }
        }
        int expectedCalls = failure instanceof PaymentAPIException || failure instanceof OperationException ? 0 : 1;
        check(pluginCalls[0] == expectedCalls, "Unexpected number of plugin calls");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
