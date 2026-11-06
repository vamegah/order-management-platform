package com.example.omp.gateway.clients.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

@Schema(description = "Request body for creating a DRAFT order")
public record CreateOrderRequest(
        @NotNull @Schema(description = "Customer id (the order is for this customer)")
                UUID customerId,
        @NotNull @Valid @Schema(description = "Shipping address snapshot")
                ShippingAddressRequest shippingAddress,
        @NotEmpty @Valid @Schema(description = "Order lines. At least one required.")
                List<LineRequest> lines,
        @Size(max = 2000) @Schema(description = "Optional notes") String notes) {
    @Schema(description = "Shipping address in a create-order request")
    public record ShippingAddressRequest(
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @NotBlank @Size(max = 100) String city,
            @Size(max = 100) String state,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String country) {}

    @Schema(description = "A line in a create-order request")
    public record LineRequest(@NotNull UUID productId, @Positive int quantity) {}
}
