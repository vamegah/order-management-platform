package com.example.omp.order.clients;

import com.example.omp.order.clients.dto.CustomerDto;
import com.example.omp.order.config.FeignAuthForwardingConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** HTTP client for {@code customer-service}. */
@FeignClient(
        name = "customer-service",
        url = "${omp.clients.customer-service.url}",
        configuration = FeignAuthForwardingConfig.class)
public interface CustomerClient {

    @GetMapping("/api/v1/customers/{id}")
    @CircuitBreaker(name = "customerClient")
    CustomerDto getCustomer(@PathVariable("id") UUID id);

    @GetMapping("/api/v1/customers/me")
    @CircuitBreaker(name = "customerClient")
    CustomerDto getCurrentCustomer();
}
