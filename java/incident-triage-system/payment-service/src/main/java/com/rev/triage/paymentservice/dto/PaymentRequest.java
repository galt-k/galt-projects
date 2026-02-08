package com.rev.triage.paymentservice.dto;

import java.math.BigDecimal;

public record PaymentRequest(
    Long orderId,
    BigDecimal amount
) {}
