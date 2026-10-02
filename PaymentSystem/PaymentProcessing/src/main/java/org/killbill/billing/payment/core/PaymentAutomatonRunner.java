package org.killbill.billing.payment.core;

import org.example.OperationException;
import org.example.PaymentAPIException;
import org.example.statemachine.PaymentStateContext;

@FunctionalInterface
public interface PaymentAutomatonRunner {
    /** Resolves an existing payment's method when needed and stores the resulting transaction in the context. */
    void run(PaymentStateContext context) throws OperationException, PaymentAPIException;
}
