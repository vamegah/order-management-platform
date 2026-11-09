package com.example.omp.order.web;

import com.example.omp.order.dto.TerminalReservationDispositionRequest;
import com.example.omp.order.dto.TerminalReservationDispositionView;
import com.example.omp.order.service.TerminalReservationDispositionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/order-reservation-repairs/{orderId}/terminal-dispositions")
@PreAuthorize("hasRole('admin')")
public class TerminalReservationDispositionController {
    private final TerminalReservationDispositionService service;

    public TerminalReservationDispositionController(TerminalReservationDispositionService service) {
        this.service = service;
    }

    @GetMapping
    public TerminalReservationDispositionView inspect(@PathVariable UUID orderId) {
        return service.inspect(orderId);
    }

    @PostMapping
    public TerminalReservationDispositionView record(
            @PathVariable UUID orderId,
            @Valid @RequestBody TerminalReservationDispositionRequest request,
            Authentication operator) {
        return service.record(orderId, request, operator == null ? null : operator.getName());
    }
}
