package org.example.persistence;

import org.example.Payment;

import java.util.List;
import java.util.Objects;

/** A consistent view captured while atomically registering or finding a checkout key. */
public record PaymentRegistration(boolean created, Payment payment, List<PaymentTransactionModel> transactions) {
    public PaymentRegistration {
        Objects.requireNonNull(payment, "payment");
        transactions = List.copyOf(transactions);
    }
}
