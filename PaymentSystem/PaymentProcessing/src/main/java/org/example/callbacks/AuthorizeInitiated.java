package org.example.callbacks;

import org.example.AbstractPaymentLeavingStateCallback;
import org.example.statemachine.PaymentStateContext;
import org.example.persistence.PaymentWorkflowDAOHelper;

/** Persists the authorization intent when leaving the initial state. */
public final class AuthorizeInitiated extends AbstractPaymentLeavingStateCallback {
    public AuthorizeInitiated(PaymentWorkflowDAOHelper daoHelper, PaymentStateContext paymentStateContext) {
        super(daoHelper, paymentStateContext);
    }
}
