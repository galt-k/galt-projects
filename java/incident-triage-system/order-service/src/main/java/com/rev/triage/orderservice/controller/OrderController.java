package com.rev.triage.orderservice.controller;

import com.rev.triage.orderservice.dto.CreateOrderRequest;
import com.rev.triage.orderservice.dto.OrderResponse;
import com.rev.triage.orderservice.service.OrderService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public OrderResponse createOrder(@RequestBody CreateOrderRequest request) {
        return orderService.createOrder(request);
    }
}
