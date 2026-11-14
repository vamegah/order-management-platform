package com.example.omp.gateway.clients;

import com.example.omp.gateway.clients.dto.*;
import com.example.omp.gateway.config.FeignAuthForwardingConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(
        name = "order-service",
        url = "${omp.clients.order-service.url}",
        configuration = FeignAuthForwardingConfig.class)
public interface OrderClient {

    @GetMapping("/api/v1/orders/{id}")
    @CircuitBreaker(name = "orderClient")
    OrderClientDto getOrder(
            @PathVariable("id") UUID id, @RequestParam(defaultValue = "true") boolean withHistory);

    @GetMapping("/api/v1/orders/number/{orderNumber}")
    @CircuitBreaker(name = "orderClient")
    OrderClientDto getOrderByNumber(
            @PathVariable("orderNumber") String orderNumber,
            @RequestParam(defaultValue = "true") boolean withHistory);

    @GetMapping("/api/v1/orders")
    @CircuitBreaker(name = "orderClient")
    OrderPageDto listOrders(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort);

    @GetMapping("/api/v1/orders")
    @CircuitBreaker(name = "orderClient")
    OrderPageDto listOrdersByPlacement(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) java.time.Instant placedAfter,
            @RequestParam(required = false) java.time.Instant placedBefore);

    @PostMapping(value = "/api/v1/orders", consumes = "application/json")
    @CircuitBreaker(name = "orderClient")
    OrderClientDto createOrder(@RequestBody CreateOrderRequest request);

    @PostMapping(value = "/api/v1/orders/{id}/place", consumes = "application/json")
    @CircuitBreaker(name = "orderClient")
    OrderClientDto placeOrder(@PathVariable("id") UUID id);

    @PostMapping(value = "/api/v1/orders/{id}/confirm", consumes = "application/json")
    @CircuitBreaker(name = "orderClient")
    OrderClientDto confirmOrder(
            @PathVariable("id") UUID id, @RequestBody(required = false) ReasonRequest body);

    @PostMapping(value = "/api/v1/orders/{id}/ship", consumes = "application/json")
    @CircuitBreaker(name = "orderClient")
    OrderClientDto shipOrder(
            @PathVariable("id") UUID id, @RequestBody(required = false) ReasonRequest body);

    @PostMapping(value = "/api/v1/orders/{id}/deliver", consumes = "application/json")
    @CircuitBreaker(name = "orderClient")
    OrderClientDto deliverOrder(
            @PathVariable("id") UUID id, @RequestBody(required = false) ReasonRequest body);

    @PostMapping(value = "/api/v1/orders/{id}/cancel", consumes = "application/json")
    @CircuitBreaker(name = "orderClient")
    OrderClientDto cancelOrder(
            @PathVariable("id") UUID id, @RequestBody(required = false) ReasonRequest body);

    record ReasonRequest(String reason) {}
}
