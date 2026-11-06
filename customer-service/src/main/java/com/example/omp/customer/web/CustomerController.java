package com.example.omp.customer.web;

import com.example.omp.customer.dto.*;
import com.example.omp.customer.service.CustomerService;
import com.example.omp.shared.web.PageResponse;
import com.example.omp.shared.web.pagination.PageableResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers", description = "Customer profile operations")
@SecurityRequirement(name = "bearerAuth")
public class CustomerController {

    private final CustomerService customerService;
    private final PageableResolver pageableResolver;

    public CustomerController(CustomerService customerService, PageableResolver pageableResolver) {
        this.customerService = customerService;
        this.pageableResolver = pageableResolver;
    }

    // -------------------------------------------------------------------------
    // List & search
    // -------------------------------------------------------------------------

    @GetMapping("/batch")
    @PreAuthorize(
            "hasRole('admin') or (#ids.size() == 1 and #ids[0].toString() == authentication.token.claims['customer_id'])")
    @Operation(
            summary =
                    "Read 1 to 100 customers in request order; non-admin callers may request only themselves")
    public java.util.List<CustomerDto> batch(@RequestParam java.util.List<UUID> ids) {
        return customerService.getBatch(ids);
    }

    @GetMapping
    @PreAuthorize("hasRole('admin')")
    @Operation(
            summary = "List customers (admin only)",
            description = "Paginated listing with optional filters.")
    public PageResponse<CustomerDto> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String nameQuery,
            @RequestParam(required = false) String emailDomain,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        CustomerFilter filter = new CustomerFilter(status, nameQuery, emailDomain);
        return customerService.list(filter, pageableResolver.resolve(page, size, sort));
    }

    // -------------------------------------------------------------------------
    // Self-service
    // -------------------------------------------------------------------------

    @GetMapping("/me")
    @Operation(
            summary = "Get the current authenticated customer",
            description = "Returns the customer linked to the JWT's `sub` claim.")
    public CustomerDto me(@AuthenticationPrincipal Jwt jwt) {
        return customerService.getByKeycloakSub(jwt.getSubject());
    }

    // -------------------------------------------------------------------------
    // Single resource
    // -------------------------------------------------------------------------

    @GetMapping("/{id}")
    @PreAuthorize(
            "hasRole('admin') or #id.toString() == authentication.token.claims['customer_id']")
    @Operation(summary = "Get a customer by ID")
    public CustomerDto getById(@PathVariable UUID id) {
        return customerService.getById(id);
    }

    @GetMapping("/email/{email}")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Get a customer by email (admin only)")
    public CustomerDto getByEmail(@PathVariable String email) {
        return customerService.getByEmail(email);
    }

    // -------------------------------------------------------------------------
    // Writes
    // -------------------------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Create a customer (admin only)")
    public ResponseEntity<CustomerDto> create(
            @Valid @RequestBody CreateCustomerRequest request, UriComponentsBuilder uriBuilder) {
        CustomerDto created = customerService.create(request);
        URI location =
                uriBuilder.path("/api/v1/customers/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Update a customer (admin only)")
    public CustomerDto update(
            @PathVariable UUID id, @Valid @RequestBody UpdateCustomerRequest request) {
        return customerService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Deactivate a customer (admin only, soft delete)")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        customerService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/link-keycloak")
    @Operation(
            summary = "Link the current JWT's sub to a customer",
            description = "Idempotent if already linked to the same sub.")
    public CustomerDto linkKeycloak(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return customerService.linkKeycloakSub(id, jwt.getSubject());
    }
}
