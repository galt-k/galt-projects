package org.example.workflow;

import org.example.OperationException;
import org.example.PaymentAPIException;
import org.example.statemachine.PaymentStateContext;
import org.example.persistence.PaymentWorkflowDAOHelper;
import org.example.State;
import org.example.plugin.PaymentPluginAPI;
import org.example.plugin.PaymentPluginException;
import org.example.plugin.PaymentPluginStatus;
import org.example.retry.RetryExecutionException;

import java.util.Objects;

public abstract class OperationCallbackBase implements OperationCallback {
    private static final System.Logger LOGGER = System.getLogger(OperationCallbackBase.class.getName());

    protected final PaymentWorkflowDAOHelper daoHelper;
    protected final PaymentStateContext paymentStateContext;
    protected final PaymentPluginAPI paymentPluginApi;

    public OperationCallbackBase(PaymentWorkflowDAOHelper daoHelper,
                                 PaymentStateContext paymentStateContext,
                                 PaymentPluginAPI paymentPluginApi) {
        this.daoHelper = Objects.requireNonNull(daoHelper, "daoHelper");
        this.paymentStateContext = Objects.requireNonNull(paymentStateContext, "paymentStateContext");
        this.paymentPluginApi = Objects.requireNonNull(paymentPluginApi, "paymentPluginApi");
    }

    @Override
    public final OperationResult doOperation(State currentState) throws OperationException {
        paymentStateContext.setException(null);
        paymentStateContext.setPaymentInfoPlugin(null);
        paymentStateContext.setGatewayStatus(null);
        try {
            PaymentPluginStatus status = doOperationCallback(paymentPluginApi, paymentStateContext);
            if (status == null) {
                throw new OperationException("Payment plugin returned no status");
            }
            paymentStateContext.setGatewayStatus(status);
            return switch (status) {
                case PROCESSED -> OperationResult.SUCCESS;
                case PENDING -> OperationResult.PENDING;
                case ERROR, CANCELED -> OperationResult.FAILURE;
            };
        } catch (PaymentPluginException | PaymentAPIException | RetryExecutionException exception) {
            recordException(exception);
            return OperationResult.EXCEPTION;
        } catch (OperationException exception) {
            recordException(exception);
            throw exception;
        }
    }

    protected abstract PaymentPluginStatus doOperationCallback(PaymentPluginAPI pluginApi,
                                                               PaymentStateContext context)
            throws PaymentPluginException, PaymentAPIException, OperationException;

    private void recordException(Exception exception) {
        paymentStateContext.setException(exception);
        LOGGER.log(System.Logger.Level.ERROR,
                "Payment plugin operation failed for payment " + paymentStateContext.getPaymentId(), exception);
    }
}
