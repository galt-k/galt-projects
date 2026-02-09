package com.rev.triage.productservice.service;

import com.rev.triage.productservice.dto.ProductResponse;
import com.rev.triage.productservice.entity.Product;
import com.rev.triage.productservice.repository.ProductRepository;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Observed(name = "product.getAll", contextualName = "get-all-products",
              lowCardinalityKeyValues = {"product.operation", "list"})
    public List<ProductResponse> getAllProducts() {
        log.info("Fetching all products");
        return productRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Observed(name = "product.getById", contextualName = "get-product-by-id",
              lowCardinalityKeyValues = {"product.operation", "lookup"})
    public ProductResponse getProductById(Long id) {
        log.info("Fetching product {}", id);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
        return toResponse(product);
    }

    private ProductResponse toResponse(Product p) {
        return new ProductResponse(p.getId(), p.getName(), p.getPrice(), p.getStockQuantity());
    }
}
