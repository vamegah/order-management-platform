package com.example.omp.notification.durable;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/event-recovery")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class EventRecoveryController {
    private final EventRecoveryService service;

    public EventRecoveryController(EventRecoveryService service) {
        this.service = service;
    }

    public record Replay(
            @NotBlank @Size(max = 500) String reason,
            @Size(max = 262144) String correctedPayload) {}

    @GetMapping("/quarantine")
    public List<Map<String, Object>> quarantine(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(defaultValue = "false") boolean includeReplayed) {
        return service.quarantine(limit, after, includeReplayed);
    }

    @GetMapping("/quarantine/{id}")
    public Map<String, Object> inspect(@PathVariable UUID id) {
        return service.inspect(id);
    }

    @GetMapping("/dispatches")
    public List<Map<String, Object>> failed(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.failed(limit);
    }

    @PostMapping("/quarantine/{id}/replay")
    public void replay(
            @PathVariable UUID id,
            @Valid @RequestBody Replay request,
            @AuthenticationPrincipal Jwt jwt) {
        service.replay(id, jwt.getSubject(), request.reason(), request.correctedPayload());
    }

    @PostMapping("/dispatches/{id}/retry")
    public void retry(
            @PathVariable UUID id,
            @Valid @RequestBody Replay request,
            @AuthenticationPrincipal Jwt jwt) {
        service.retry(id, jwt.getSubject(), request.reason());
    }
}
