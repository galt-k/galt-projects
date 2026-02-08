package com.rev.triage.orderservice.client;

import com.rev.triage.orderservice.dto.PaymentRequest;
import com.rev.triage.orderservice.dto.PaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PaymentServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceClient.class);
    private final RestClient paymentRestClient;

    public PaymentServiceClient(RestClient paymentRestClient) {
        this.paymentRestClient = paymentRestClient;
    }

    public PaymentResponse processPayment(PaymentRequest request) {
        log.info("Requesting payment for order {} amount {}", request.orderId(), request.amount());
        return paymentRestClient.post()
                .uri("/payments")
                .body(request)
                .retrieve()
                .body(PaymentResponse.class);
    }
}
