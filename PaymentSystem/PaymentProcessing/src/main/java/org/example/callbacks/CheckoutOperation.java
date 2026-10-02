package org.example.callbacks;

import org.example.PaymentAPIException;
import org.example.statemachine.PaymentStateContext;
import org.example.persistence.PaymentWorkflowDAOHelper;
import org.example.plugin.PaymentPluginAPI;
import org.example.plugin.PaymentPluginAPIException;
import org.example.plugin.PaymentPluginStatus;
import org.example.workflow.OperationCallbackBase;

public final class CheckoutOperation extends OperationCallbackBase {
    public CheckoutOperation(PaymentWorkflowDAOHelper daoHelper,
                               PaymentStateContext paymentStateContext,
                               PaymentPluginAPI paymentPluginApi) {
        super(daoHelper, paymentStateContext, paymentPluginApi);
    }

    @Override
    protected PaymentPluginStatus doOperationCallback(PaymentPluginAPI pluginApi, PaymentStateContext context)
            throws PaymentPluginAPIException, PaymentAPIException {
        return pluginApi.checkoutPayment(
                context.getAccountId(),
                context.getPaymentId(),
                context.getTransactionId(),
                context.getPaymentMethodId(),
                context.getAmount(),
                context.getCurrency(),
                context.getProperties(),
                context.getCallContext());
    }
}
