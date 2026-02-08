package com.rev.triage.orderservice.dto;

import java.util.List;

public record CreateOrderRequest(
    List<OrderItemRequest> items
) {
    public record OrderItemRequest(
        Long productId,
        Integer quantity
    ) {}
}
