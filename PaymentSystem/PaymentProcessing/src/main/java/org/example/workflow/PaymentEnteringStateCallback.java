package org.example.workflow;

import org.example.LeavingStateCallback;
import org.example.OperationException;
import org.example.PaymentAPIException;
import org.example.statemachine.PaymentStateContext;
import org.example.persistence.PaymentWorkflowDAOHelper;
import org.example.persistence.PaymentTransactionModel;
import org.example.State;
import org.example.TransactionStatus;
import org.example.TransactionState;
import org.example.plugin.PaymentTransactionInfoPlugin;
import org.springframework.dao.DataAccessException;

import java.util.Objects;
import java.time.Instant;

public abstract class PaymentEnteringStateCallback implements EnteringStateCallback {
    protected final PaymentWorkflowDAOHelper daoHelper;
    protected final PaymentStateContext paymentStateContext;

    public PaymentEnteringStateCallback(PaymentWorkflowDAOHelper daoHelper,
                                        PaymentStateContext paymentStateContext) {
        this.daoHelper = Objects.requireNonNull(daoHelper, "daoHelper");
        this.paymentStateContext = Objects.requireNonNull(paymentStateContext, "paymentStateContext");
    }

    @Override
    public void enteringState(State newState,
                              OperationCallback operationCallback,
                              OperationResult operationResult,
                              LeavingStateCallback leavingStateCallback) throws OperationException {
        if (operationResult == null) {
            throw new OperationException("Cannot persist a payment outcome without an operation result");
        }

        TransactionStatus targetStatus = switch (operationResult) {
            case SUCCESS -> TransactionStatus.SUCCESS;
            case FAILURE -> TransactionStatus.FAILURE;
            case EXCEPTION -> TransactionStatus.UNKNOWN;
            case PENDING -> TransactionStatus.UNKNOWN;
        };

        PaymentTransactionInfoPlugin paymentInfo = paymentStateContext.getPaymentInfoPlugin();
        Exception operationException = paymentStateContext.getException();
        // Gateway exceptions may arrive without a response; outcome persistence must still run.
        String gatewayTransactionId = paymentInfo == null ? null : paymentInfo.gatewayTransactionId();
        String gatewayResponseCode = paymentInfo == null ? null : paymentInfo.gatewayResponseCode();
        String gatewayErrorMessage = paymentInfo == null ? null : paymentInfo.gatewayErrorMessage();
        if (gatewayErrorMessage == null && operationResult == OperationResult.EXCEPTION && operationException != null) {
            gatewayErrorMessage = operationException.getMessage();
        }

        PaymentTransactionModel transaction = paymentStateContext.getPaymentTransaction();
        if (transaction == null) {
            throw new OperationException("Cannot persist an outcome without a payment transaction");
        }
        try {
            Instant processedAt = daoHelper.updatePaymentTransactionStatus(
                    paymentStateContext.getAccountId(),
                    paymentStateContext.getPaymentId(),
                    paymentStateContext.getTransactionId(),
                    targetStatus,
                    gatewayTransactionId,
                    gatewayResponseCode,
                    gatewayErrorMessage,
                    paymentStateContext.getGatewayStatus());
            paymentStateContext.setPaymentTransaction(new PaymentTransactionModel(
                    transaction.id(), transaction.accountId(), transaction.paymentId(),
                    transaction.transactionType(), transaction.amount(), transaction.currency(), targetStatus,
                    gatewayTransactionId == null ? transaction.gatewayTransactionId() : gatewayTransactionId,
                    gatewayResponseCode, gatewayErrorMessage, transaction.createdAt(), processedAt,
                    TransactionState.forOutcome(transaction.transactionType(), targetStatus),
                    paymentStateContext.getGatewayStatus()));
        } catch (PaymentAPIException | DataAccessException exception) {
            throw new OperationException("Payment outcome persistence failed", exception);
        }
    }
}
