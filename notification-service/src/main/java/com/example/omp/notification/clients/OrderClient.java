package com.example.omp.notification.clients;

import com.example.omp.notification.clients.dto.OrderDto;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        name = "order-service",
        url = "${omp.clients.order-service.url}",
        configuration = NotificationFeignConfig.class)
public interface OrderClient {

    @GetMapping("/api/v1/orders/{id}")
    OrderDto getOrder(@PathVariable("id") UUID id);
}
