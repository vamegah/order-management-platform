package com.example.omp.order.web;

import com.example.omp.order.dto.RecoveryAdoptionRequest;
import com.example.omp.order.dto.RecoveryAdoptionResult;
import com.example.omp.order.service.CheckoutRecoveryAdoption;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/order-recovery")
@PreAuthorize("hasRole('admin')")
public class CheckoutRecoveryAdoptionController {
    private final CheckoutRecoveryAdoption recovery;

    public CheckoutRecoveryAdoptionController(CheckoutRecoveryAdoption recovery) {
        this.recovery = recovery;
    }

    @PostMapping("/{orderId}/adopt")
    public RecoveryAdoptionResult adopt(
            @PathVariable UUID orderId,
            @Valid @RequestBody RecoveryAdoptionRequest request,
            Authentication operator) {
        return recovery.adopt(orderId, request, operator == null ? null : operator.getName());
    }
}
