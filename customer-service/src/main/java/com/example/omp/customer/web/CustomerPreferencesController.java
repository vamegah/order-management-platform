package com.example.omp.customer.web;

import com.example.omp.customer.dto.CustomerPreferencesDto;
import com.example.omp.customer.dto.UpdatePreferencesRequest;
import com.example.omp.customer.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/preferences")
@SecurityRequirement(name = "bearerAuth")
public class CustomerPreferencesController {
    private final CustomerService customers;

    public CustomerPreferencesController(CustomerService customers) {
        this.customers = customers;
    }

    @PutMapping
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Set supplied customer preferences (administrator)")
    public CustomerPreferencesDto set(
            @PathVariable UUID customerId, @Valid @RequestBody UpdatePreferencesRequest request) {
        return customers.updatePreferences(customerId, request);
    }
}
