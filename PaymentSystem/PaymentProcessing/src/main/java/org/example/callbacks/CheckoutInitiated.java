package org.example.callbacks;

import org.example.AbstractPaymentLeavingStateCallback;
import org.example.statemachine.PaymentStateContext;
import org.example.persistence.PaymentWorkflowDAOHelper;

/** Persists the immediate checkout intent when leaving the initial state. */
public final class CheckoutInitiated extends AbstractPaymentLeavingStateCallback {
    public CheckoutInitiated(PaymentWorkflowDAOHelper daoHelper, PaymentStateContext paymentStateContext) {
        super(daoHelper, paymentStateContext);
    }
}
