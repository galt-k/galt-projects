package com.rev.triage.orderservice.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderResponse(
    Long id,
    String status,
    BigDecimal totalAmount,
    Long paymentId,
    LocalDateTime createdAt,
    List<OrderItemResponse> items
) {
    public record OrderItemResponse(
        Long productId,
        String productName,
        Integer quantity,
        BigDecimal unitPrice
    ) {}
}
