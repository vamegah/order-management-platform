package com.example.omp.payment.web;

import com.example.omp.payment.service.PaymentWebhookInbox;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments/webhook-events")
@PreAuthorize("hasRole('admin')")
public class ProviderEventRecoveryController {
    private final PaymentWebhookInbox inbox;

    public ProviderEventRecoveryController(PaymentWebhookInbox inbox) {
        this.inbox = inbox;
    }

    @GetMapping
    public List<Map<String, Object>> attention() {
        return inbox.attention();
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<Map<String, String>> replay(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody Replay request) {
        inbox.replay(id, jwt.getSubject(), request.reason());
        return ResponseEntity.accepted().body(Map.of("status", "queued"));
    }

    public record Replay(@NotBlank @Size(max = 500) String reason) {}

    @ExceptionHandler(PaymentWebhookInbox.ConflictingEvent.class)
    public ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(409).body(Map.of("error", "event_not_replayable"));
    }
}
