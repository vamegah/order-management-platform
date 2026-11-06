package com.example.omp.notification.clients;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        name = "payment-service",
        url = "${omp.clients.payment-service.url:http://localhost:8080}",
        configuration = NotificationFeignConfig.class)
public interface PaymentClient {
    @GetMapping("/api/v1/payments/{id}")
    PaymentOwner getPayment(@PathVariable("id") UUID id);

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentOwner(UUID id, UUID customerId) {}
}
