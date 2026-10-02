package org.example.states;

import org.example.Payment;
import org.example.PaymentEvent;
import org.example.State;

public final class Captured extends AbstractPaymentState {
    public static final Captured INSTANCE = new Captured();
    private Captured() {}

    @Override public String name() { return "CAPTURED"; }

    @Override
    public State advance(Payment payment, PaymentEvent event) {
        return switch (event) {
            case PaymentEvent.LedgerPosted e -> Succeeded.INSTANCE;
            default -> super.advance(payment, event);
        };
    }
}