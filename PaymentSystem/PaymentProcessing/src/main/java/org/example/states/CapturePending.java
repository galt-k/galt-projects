package org.example.states;

import org.example.Payment;
import org.example.PaymentEvent;
import org.example.State;

public final class CapturePending extends AbstractPaymentState {
    public static final CapturePending INSTANCE = new CapturePending();
    private CapturePending() {}

    @Override public String name() { return "CAPTURE_PENDING"; }

    @Override
    public State advance(Payment payment, PaymentEvent event) {
        return switch (event) {
            case PaymentEvent.CaptureSucceeded e -> Captured.INSTANCE;
            case PaymentEvent.CaptureFailed e    -> Failed.INSTANCE;
            default -> super.advance(payment, event);
        };
    }

    @Override
    public State recover(Payment payment) {
        return Failed.INSTANCE;
    }
}
