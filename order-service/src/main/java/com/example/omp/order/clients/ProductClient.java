package com.example.omp.order.clients;

import com.example.omp.order.clients.dto.ProductDto;
import com.example.omp.order.config.FeignAuthForwardingConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * HTTP client for {@code product-service}.
 *
 * <p>Configured via {@code omp.clients.product-service.url} in application.yml. The auth forwarding
 * interceptor ({@link FeignAuthForwardingConfig}) propagates the JWT and trace context.
 *
 * <p>Each method is wrapped in a Resilience4j circuit breaker named {@code productClient}. On
 * repeated failures, calls short-circuit.
 */
@FeignClient(
        name = "product-service",
        url = "${omp.clients.product-service.url}",
        configuration = FeignAuthForwardingConfig.class)
public interface ProductClient {

    /** Fetches a product by id. Returns 404 if not found. */
    @GetMapping("/api/v1/products/{id}")
    @CircuitBreaker(name = "productClient")
    ProductDto getProduct(@PathVariable("id") UUID id);
}
