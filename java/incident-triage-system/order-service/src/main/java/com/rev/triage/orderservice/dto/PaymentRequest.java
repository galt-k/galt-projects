package com.rev.triage.orderservice.dto;

import java.math.BigDecimal;

public record PaymentRequest(
    Long orderId,
    BigDecimal amount
) {}
