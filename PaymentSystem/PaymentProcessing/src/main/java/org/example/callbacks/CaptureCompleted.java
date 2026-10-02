package org.example.callbacks;

import org.example.statemachine.PaymentStateContext;
import org.example.persistence.PaymentWorkflowDAOHelper;
import org.example.workflow.PaymentEnteringStateCallback;

public final class CaptureCompleted extends PaymentEnteringStateCallback {
    public CaptureCompleted(PaymentWorkflowDAOHelper daoHelper, PaymentStateContext paymentStateContext) {
        super(daoHelper, paymentStateContext);
    }
}
