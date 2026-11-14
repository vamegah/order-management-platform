package com.example.omp.customer.web;

import com.example.omp.customer.dto.AddressDto;
import com.example.omp.customer.dto.CreateAddressRequest;
import com.example.omp.customer.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/addresses")
@Tag(name = "Addresses", description = "Customer address operations")
@SecurityRequirement(name = "bearerAuth")
public class AddressController {

    private final CustomerService customerService;

    public AddressController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    @PreAuthorize(
            "hasRole('admin') or #customerId.toString() == authentication.token.claims['customer_id']")
    @Operation(summary = "List a customer's addresses")
    public List<AddressDto> list(@PathVariable UUID customerId) {
        return customerService.listAddresses(customerId);
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Add an address to a customer (admin only)")
    public ResponseEntity<AddressDto> add(
            @PathVariable UUID customerId,
            @Valid @RequestBody CreateAddressRequest request,
            UriComponentsBuilder uriBuilder) {
        AddressDto created = customerService.addAddress(customerId, request);
        URI location =
                uriBuilder
                        .path("/api/v1/customers/{customerId}/addresses/{id}")
                        .buildAndExpand(customerId, created.id())
                        .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{addressId}")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Update an address (admin only)")
    public AddressDto update(
            @PathVariable UUID customerId,
            @PathVariable UUID addressId,
            @Valid @RequestBody CreateAddressRequest request) {
        return customerService.updateAddress(customerId, addressId, request);
    }

    @DeleteMapping("/{addressId}")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Remove an address (admin only)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> remove(
            @PathVariable UUID customerId, @PathVariable UUID addressId) {
        customerService.removeAddress(customerId, addressId);
        return ResponseEntity.noContent().build();
    }
}
