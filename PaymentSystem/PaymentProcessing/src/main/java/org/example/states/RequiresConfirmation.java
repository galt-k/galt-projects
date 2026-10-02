package org.example.states;

import org.example.Payment;
import org.example.PaymentEvent;
import org.example.State;

public final class RequiresConfirmation extends AbstractPaymentState {
    public static final RequiresConfirmation INSTANCE = new RequiresConfirmation();
    private RequiresConfirmation() {}

    @Override
    public String name() {
        return "REQUIRES_CONFIRMATION";
    }

    @Override
    public State advance(Payment payment, PaymentEvent event) {
        return switch (event) {
            case PaymentEvent.AuthorizationStarted e -> AuthorizationPending.INSTANCE;
            case PaymentEvent.FraudRejected e        -> Failed.INSTANCE;
            case PaymentEvent.Cancelled e            -> Cancelled.INSTANCE;
            default -> super.advance(payment, event);
        };
    }
}
