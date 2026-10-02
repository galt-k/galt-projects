package org.example;

public interface State {
    State advance(Payment payment, PaymentEvent event);
    State recover(Payment payment);
    String name();
}
