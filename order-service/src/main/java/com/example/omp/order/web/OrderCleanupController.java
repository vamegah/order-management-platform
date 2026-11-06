package com.example.omp.order.web;

import com.example.omp.order.service.OrderCleanupQueue;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/order-cleanup")
@PreAuthorize("hasRole('admin')")
public class OrderCleanupController {
    private final OrderCleanupQueue queue;

    public OrderCleanupController(OrderCleanupQueue queue) {
        this.queue = queue;
    }

    @PostMapping("/{orderId}/retry")
    public Map<String, Integer> retry(@PathVariable UUID orderId) {
        return Map.of("completed", queue.retryOrder(orderId, 100));
    }
}
