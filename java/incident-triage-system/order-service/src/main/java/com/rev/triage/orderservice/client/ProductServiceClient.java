package com.rev.triage.orderservice.client;

import com.rev.triage.orderservice.dto.ProductResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ProductServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceClient.class);
    private final RestClient productRestClient;

    public ProductServiceClient(RestClient productRestClient) {
        this.productRestClient = productRestClient;
    }

    public ProductResponse getProduct(Long productId) {
        log.info("Fetching product {}", productId);
        return productRestClient.get()
                .uri("/products/{id}", productId)
                .retrieve()
                .body(ProductResponse.class);
    }
}
