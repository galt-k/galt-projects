package org.example;

import org.example.persistence.PaymentTransactionModel;

import org.example.persistence.PaymentWorkflowDAOHelper;

import org.example.statemachine.PaymentStateContext;

import org.springframework.dao.DataAccessException;

import java.util.Objects;

public abstract class AbstractPaymentLeavingStateCallback implements LeavingStateCallback {
    protected final PaymentWorkflowDAOHelper daoHelper;
    protected final PaymentStateContext paymentStateContext;

    public AbstractPaymentLeavingStateCallback(PaymentWorkflowDAOHelper daoHelper,
                                               PaymentStateContext paymentStateContext) {
        this.daoHelper = Objects.requireNonNull(daoHelper, "daoHelper");
        this.paymentStateContext = Objects.requireNonNull(paymentStateContext, "paymentStateContext");
    }

    @Override
    public void leavingState(State oldState) throws OperationException {
        try {
            PaymentTransactionModel transaction = daoHelper.insertPaymentTransaction(
                    paymentStateContext.getAccountId(),
                    paymentStateContext.getPaymentId(),
                    paymentStateContext.getTransactionType(),
                    paymentStateContext.getAmount(),
                    paymentStateContext.getCurrency(),
                    TransactionStatus.UNKNOWN);
            paymentStateContext.setPaymentTransaction(transaction);
        } catch (PaymentAPIException | DataAccessException exception) {
            throw new OperationException("Unknown intent persistence failed", exception);
        }
    }
}
