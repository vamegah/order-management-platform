package com.example.omp.order.web;

import com.example.omp.order.service.OrderLifecycleStore;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/order-lifecycle")
@PreAuthorize("hasRole('admin')")
public class OrderLifecycleController {
    private final OrderLifecycleStore store;

    public OrderLifecycleController(OrderLifecycleStore store) {
        this.store = store;
    }

    @GetMapping("/{id}")
    public Map<String, Object> inspect(@PathVariable UUID id) {
        return store.inspect(id);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Map<String, String>> retry(
            @PathVariable UUID id,
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal Jwt jwt) {
        store.retry(id, jwt.getSubject(), request.get("reason"));
        return ResponseEntity.accepted().body(Map.of("status", "RECONCILIATION_REQUESTED"));
    }
}
