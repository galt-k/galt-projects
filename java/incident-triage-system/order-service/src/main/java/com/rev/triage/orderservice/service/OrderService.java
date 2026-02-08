package com.rev.triage.orderservice.service;

import com.rev.triage.orderservice.client.PaymentServiceClient;
import com.rev.triage.orderservice.client.ProductServiceClient;
import com.rev.triage.orderservice.dto.*;
import com.rev.triage.orderservice.entity.Order;
import com.rev.triage.orderservice.entity.OrderItem;
import com.rev.triage.orderservice.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ProductServiceClient productClient;
    private final PaymentServiceClient paymentClient;

    public OrderService(OrderRepository orderRepository,
                        ProductServiceClient productClient,
                        PaymentServiceClient paymentClient) {
        this.orderRepository = orderRepository;
        this.productClient = productClient;
        this.paymentClient = paymentClient;
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        log.info("Creating order with {} items", request.items().size());

        Order order = new Order();
        order.setStatus(Order.OrderStatus.CREATED);
        order.setTotalAmount(BigDecimal.ZERO);
        order.setCreatedAt(LocalDateTime.now());

        BigDecimal total = BigDecimal.ZERO;

        // Validate each product by calling product-service (generates HTTP spans)
        for (CreateOrderRequest.OrderItemRequest itemReq : request.items()) {
            ProductResponse product = productClient.getProduct(itemReq.productId());

            if (product.stockQuantity() < itemReq.quantity()) {
                throw new RuntimeException(
                    "Insufficient stock for product " + product.name()
                    + ": requested=" + itemReq.quantity()
                    + " available=" + product.stockQuantity());
            }

            OrderItem item = new OrderItem();
            item.setProductId(product.id());
            item.setProductName(product.name());
            item.setQuantity(itemReq.quantity());
            item.setUnitPrice(product.price());
            item.setOrder(order);
            order.getItems().add(item);

            total = total.add(product.price().multiply(BigDecimal.valueOf(itemReq.quantity())));
        }

        order.setTotalAmount(total);
        order.setStatus(Order.OrderStatus.PAYMENT_PENDING);
        Order savedOrder = orderRepository.save(order);

        // Call payment-service (generates another HTTP span)
        log.info("Order {} saved, requesting payment of {}", savedOrder.getId(), total);
        PaymentResponse payment = paymentClient.processPayment(
                new PaymentRequest(savedOrder.getId(), total));

        // Update order with payment result
        savedOrder.setPaymentId(payment.id());
        savedOrder.setStatus("COMPLETED".equals(payment.status())
                ? Order.OrderStatus.COMPLETED
                : Order.OrderStatus.FAILED);
        orderRepository.save(savedOrder);

        log.info("Order {} completed with payment {}", savedOrder.getId(), payment.id());
        return toResponse(savedOrder);
    }

    private OrderResponse toResponse(Order order) {
        return new OrderResponse(
            order.getId(),
            order.getStatus().name(),
            order.getTotalAmount(),
            order.getPaymentId(),
            order.getCreatedAt(),
            order.getItems().stream()
                .map(i -> new OrderResponse.OrderItemResponse(
                    i.getProductId(),
                    i.getProductName(),
                    i.getQuantity(),
                    i.getUnitPrice()))
                .toList()
        );
    }
}
