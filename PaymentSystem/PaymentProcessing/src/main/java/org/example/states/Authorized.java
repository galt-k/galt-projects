package org.example.states;

import org.example.Payment;
import org.example.PaymentEvent;
import org.example.State;

public final class Authorized extends AbstractPaymentState {
    public static final Authorized INSTANCE = new Authorized();
    private Authorized() {}

    @Override public String name() { return "AUTHORIZED"; }

    @Override
    public State advance(Payment payment, PaymentEvent event) {
        return switch (event) {
            case PaymentEvent.CaptureStarted e -> CapturePending.INSTANCE;
            case PaymentEvent.Cancelled e      -> Cancelled.INSTANCE;
            default -> super.advance(payment, event);
        };
    }
}
