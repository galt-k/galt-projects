package org.example.states;

import org.example.Payment;
import org.example.PaymentEvent;
import org.example.State;

/*
What events are legal while the payment is in Created?
Singleton
 */
public final class Created extends AbstractPaymentState {
    public static final Created INSTANCE = new Created();
    private Created() {}

    @Override
    public String name() {
        return "Created";
    }

    @Override
    public State advance(Payment payment, PaymentEvent event){
        // Given the payment and event, what are the next legal states to transiton to
        return switch (event){
            case PaymentEvent.RequiresConfirmation e -> RequiresConfirmation.INSTANCE;
            case PaymentEvent.Cancelled e -> Cancelled.INSTANCE;
            default -> super.advance(payment, event);
        };
    }
}
