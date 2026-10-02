package org.example.states;

import org.example.Payment;
import org.example.PaymentEvent;
import org.example.State;

public final class AuthorizationPending extends AbstractPaymentState {
    public static final AuthorizationPending INSTANCE = new AuthorizationPending();
    private AuthorizationPending() {}

    @Override public String name() { return "AUTHORIZATION_PENDING"; }

    @Override
    public State advance(Payment payment, PaymentEvent event) {
        return switch (event) {
            case PaymentEvent.AuthorizationSucceeded e -> Authorized.INSTANCE;
            case PaymentEvent.AuthorizationFailed e    -> Failed.INSTANCE;
            case PaymentEvent.Cancelled e              -> Cancelled.INSTANCE;
            default -> super.advance(payment, event);
        };
    }

    @Override
    public State recover(Payment payment) {
        // System decided this authorization has timed out / is stuck
        return Failed.INSTANCE;
    }

}
