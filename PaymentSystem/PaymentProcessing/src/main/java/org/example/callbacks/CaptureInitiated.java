package org.example.callbacks;

import org.example.AbstractPaymentLeavingStateCallback;
import org.example.statemachine.PaymentStateContext;
import org.example.persistence.PaymentWorkflowDAOHelper;

/** Persists the capture intent when leaving the authorized state. */
public final class CaptureInitiated extends AbstractPaymentLeavingStateCallback {
    public CaptureInitiated(PaymentWorkflowDAOHelper daoHelper, PaymentStateContext paymentStateContext) {
        super(daoHelper, paymentStateContext);
    }
}
