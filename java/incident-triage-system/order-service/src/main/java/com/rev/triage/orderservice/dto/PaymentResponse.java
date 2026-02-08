package com.rev.triage.orderservice.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponse(
    Long id,
    Long orderId,
    BigDecimal amount,
    String status,
    LocalDateTime createdAt
) {}
