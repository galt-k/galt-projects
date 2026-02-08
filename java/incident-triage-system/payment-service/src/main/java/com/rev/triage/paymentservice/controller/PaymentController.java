package com.rev.triage.paymentservice.controller;

import com.rev.triage.paymentservice.dto.PaymentRequest;
import com.rev.triage.paymentservice.dto.PaymentResponse;
import com.rev.triage.paymentservice.service.PaymentService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public PaymentResponse processPayment(@RequestBody PaymentRequest request) {
        return paymentService.processPayment(request);
    }
}
