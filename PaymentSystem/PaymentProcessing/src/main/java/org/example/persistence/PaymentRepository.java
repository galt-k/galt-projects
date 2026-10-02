package org.example.persistence;

import org.example.Payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
    void create(Payment payment);

    /** Atomically creates the keyed payment or returns the existing payment and its transactions. */
    PaymentRegistration createOrFind(Payment payment);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findById(UUID paymentId);

    List<Payment> findAll();

    void update(Payment payment);
}
