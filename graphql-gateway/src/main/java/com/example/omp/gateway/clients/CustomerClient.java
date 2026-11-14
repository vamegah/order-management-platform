package com.example.omp.gateway.clients;

import com.example.omp.gateway.clients.dto.*;
import com.example.omp.gateway.config.FeignAuthForwardingConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(
        name = "customer-service",
        url = "${omp.clients.customer-service.url}",
        configuration = FeignAuthForwardingConfig.class)
public interface CustomerClient {

    @GetMapping("/api/v1/customers/batch")
    @CircuitBreaker(name = "customerClient")
    java.util.List<CustomerClientDto> getCustomers(@RequestParam("ids") java.util.List<UUID> ids);

    @GetMapping("/api/v1/customers/{id}")
    @CircuitBreaker(name = "customerClient")
    CustomerClientDto getCustomer(@PathVariable("id") UUID id);

    @GetMapping("/api/v1/customers/email/{email}")
    @CircuitBreaker(name = "customerClient")
    CustomerClientDto getCustomerByEmail(@PathVariable("email") String email);

    @GetMapping("/api/v1/customers/me")
    @CircuitBreaker(name = "customerClient")
    CustomerClientDto getCurrentCustomer();

    @GetMapping("/api/v1/customers")
    @CircuitBreaker(name = "customerClient")
    CustomerPageDto listCustomers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String nameQuery,
            @RequestParam(required = false) String emailDomain,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort);

    @PostMapping(value = "/api/v1/customers", consumes = "application/json")
    @CircuitBreaker(name = "customerClient")
    CustomerClientDto createCustomer(@RequestBody CreateCustomerRequest request);

    @PutMapping(value = "/api/v1/customers/{id}", consumes = "application/json")
    @CircuitBreaker(name = "customerClient")
    CustomerClientDto updateCustomer(
            @PathVariable("id") UUID id, @RequestBody UpdateCustomerRequest request);

    @DeleteMapping("/api/v1/customers/{id}")
    @CircuitBreaker(name = "customerClient")
    void deactivateCustomer(@PathVariable("id") UUID id);

    @PostMapping(value = "/api/v1/customers/{id}/addresses", consumes = "application/json")
    @CircuitBreaker(name = "customerClient")
    AddressClientDto addAddress(
            @PathVariable("id") UUID customerId, @RequestBody CreateAddressRequest request);

    @PutMapping(value = "/api/v1/customers/{id}/preferences", consumes = "application/json")
    @CircuitBreaker(name = "customerClient")
    PreferencesClientDto setPreferences(
            @PathVariable("id") UUID customerId, @RequestBody PreferencesClientDto request);
}
