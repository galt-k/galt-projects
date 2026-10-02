package org.example.callbacks;

import org.example.PaymentAPIException;
import org.example.OperationException;
import org.example.TransactionType;
import org.example.statemachine.PaymentStateContext;
import org.example.persistence.PaymentWorkflowDAOHelper;
import org.example.plugin.PaymentPluginAPI;
import org.example.plugin.PaymentPluginAPIException;
import org.example.plugin.PaymentPluginStatus;
import org.example.workflow.OperationCallbackBase;

public final class AuthorizeOperation extends OperationCallbackBase {
    public AuthorizeOperation(PaymentWorkflowDAOHelper daoHelper,
                               PaymentStateContext paymentStateContext,
                               PaymentPluginAPI paymentPluginApi) {
        super(daoHelper, paymentStateContext, paymentPluginApi);
    }

    @Override
    protected PaymentPluginStatus doOperationCallback(PaymentPluginAPI pluginApi, PaymentStateContext context)
            throws PaymentPluginAPIException, PaymentAPIException, OperationException {
        return pluginApi.authorizePayment(
                context.getAccountId(),
                context.getPaymentId(),
                context.requireOperationIdempotencyKey(TransactionType.AUTHORIZE),
                context.getPaymentMethodId(),
                context.getAmount(),
                context.getCurrency(),
                context.getProperties(),
                context.getCallContext());
    }
}
