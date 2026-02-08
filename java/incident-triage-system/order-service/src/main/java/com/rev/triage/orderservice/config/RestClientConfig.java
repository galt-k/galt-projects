package com.rev.triage.orderservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Value("${services.product.url}")
    private String productServiceUrl;

    @Value("${services.payment.url}")
    private String paymentServiceUrl;

    @Bean
    public RestClient productRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(productServiceUrl)
                .build();
    }

    @Bean
    public RestClient paymentRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(paymentServiceUrl)
                .build();
    }
}
