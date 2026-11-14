package com.example.omp.gateway.clients;

import com.example.omp.gateway.clients.dto.*;
import com.example.omp.gateway.config.FeignAuthForwardingConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(
        name = "product-service",
        url = "${omp.clients.product-service.url}",
        configuration = FeignAuthForwardingConfig.class)
public interface ProductClient {

    @GetMapping("/api/v1/products/batch")
    @CircuitBreaker(name = "productClient")
    List<ProductClientDto> getProducts(@RequestParam("ids") List<UUID> ids);

    @GetMapping("/api/v1/products/{id}")
    @CircuitBreaker(name = "productClient")
    ProductClientDto getProduct(@PathVariable("id") UUID id);

    @GetMapping("/api/v1/products/sku/{sku}")
    @CircuitBreaker(name = "productClient")
    ProductClientDto getProductBySku(@PathVariable("sku") String sku);

    @GetMapping("/api/v1/products")
    @CircuitBreaker(name = "productClient")
    ProductPageDto listProducts(
            @RequestParam(required = false) String categorySlug,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String nameQuery,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort);

    @PostMapping(value = "/api/v1/products", consumes = "application/json")
    @CircuitBreaker(name = "productClient")
    ProductClientDto createProduct(@RequestBody CreateProductRequest request);

    @PutMapping(value = "/api/v1/products/{id}", consumes = "application/json")
    @CircuitBreaker(name = "productClient")
    ProductClientDto updateProduct(
            @PathVariable("id") UUID id, @RequestBody UpdateProductRequest request);

    @DeleteMapping("/api/v1/products/{id}")
    @CircuitBreaker(name = "productClient")
    void deactivateProduct(@PathVariable("id") UUID id);

    @GetMapping("/api/v1/categories")
    @CircuitBreaker(name = "productClient")
    List<CategoryClientDto> listCategories();

    @GetMapping("/api/v1/categories/{slug}")
    @CircuitBreaker(name = "productClient")
    CategoryClientDto getCategoryBySlug(@PathVariable("slug") String slug);
}
