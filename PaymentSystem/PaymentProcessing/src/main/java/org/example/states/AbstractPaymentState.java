package org.example.states;

import org.example.Payment;
import org.example.PaymentEvent;
import org.example.State;
import org.example.exceptions.InvalidPaymentTransitionException;

public abstract class AbstractPaymentState implements State {
    @Override
    public State advance(Payment payment, PaymentEvent paymentEvent) {
        throw new InvalidPaymentTransitionException(this, paymentEvent);
    }

    @Override
    public State recover(Payment payment) {
        return this;
    }
}
