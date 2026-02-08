package com.rev.triage.paymentservice.service;

import com.rev.triage.paymentservice.dto.PaymentRequest;
import com.rev.triage.paymentservice.dto.PaymentResponse;
import com.rev.triage.paymentservice.entity.Payment;
import com.rev.triage.paymentservice.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    public PaymentResponse processPayment(PaymentRequest request) {
        log.info("Processing payment for order {} amount {}", request.orderId(), request.amount());

        // Simulate payment processing delay for interesting traces
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Payment payment = new Payment();
        payment.setOrderId(request.orderId());
        payment.setAmount(request.amount());
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        payment.setCreatedAt(LocalDateTime.now());

        Payment saved = paymentRepository.save(payment);

        return new PaymentResponse(
            saved.getId(),
            saved.getOrderId(),
            saved.getAmount(),
            saved.getStatus().name(),
            saved.getCreatedAt()
        );
    }
}
