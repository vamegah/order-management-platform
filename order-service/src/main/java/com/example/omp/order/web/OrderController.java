package com.example.omp.order.web;

import com.example.omp.order.dto.*;
import com.example.omp.order.service.OrderService;
import com.example.omp.shared.web.PageResponse;
import com.example.omp.shared.web.pagination.PageableResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order management")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    private final OrderService orderService;
    private final PageableResolver pageableResolver;

    public OrderController(OrderService orderService, PageableResolver pageableResolver) {
        this.orderService = orderService;
        this.pageableResolver = pageableResolver;
    }

    // -------------------------------------------------------------------------
    // List & get
    // -------------------------------------------------------------------------

    @GetMapping
    @Operation(
            summary =
                    "List orders: verified owner with an explicit customerId filter, or administrator")
    @PreAuthorize(
            "hasRole('admin') or (#customerId != null and #customerId.toString() == authentication.token.claims['customer_id'])")
    public PageResponse<OrderDto> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderNumber,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false)
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso =
                                    org.springframework.format.annotation.DateTimeFormat.ISO
                                            .DATE_TIME)
                    java.time.OffsetDateTime placedAfter,
            @RequestParam(required = false)
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso =
                                    org.springframework.format.annotation.DateTimeFormat.ISO
                                            .DATE_TIME)
                    java.time.OffsetDateTime placedBefore) {
        OrderFilter filter =
                new OrderFilter(
                        customerId,
                        status,
                        orderNumber,
                        placedAfter == null ? null : placedAfter.toInstant(),
                        placedBefore == null ? null : placedBefore.toInstant());
        return orderService.list(filter, pageableResolver.resolve(page, size, sort));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('admin') or @orderAuthorization.ownsOrder(#id, authentication.token)")
    @Operation(summary = "Get an order by id")
    public OrderDto getById(
            @PathVariable UUID id, @RequestParam(defaultValue = "true") boolean withHistory) {
        return orderService.getById(id, withHistory);
    }

    @GetMapping("/number/{orderNumber}")
    @PreAuthorize(
            "hasRole('admin') or @orderAuthorization.ownsOrderNumber(#orderNumber, authentication.token)")
    @Operation(summary = "Get an order by its order number")
    public OrderDto getByNumber(
            @PathVariable String orderNumber,
            @RequestParam(defaultValue = "true") boolean withHistory) {
        return orderService.getByNumber(orderNumber, withHistory);
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize(
            "hasRole('admin') or @orderAuthorization.ownsCustomer(#request.customerId(), authentication.token)")
    @Operation(summary = "Create a DRAFT order")
    public ResponseEntity<OrderDto> create(
            @Valid @RequestBody CreateOrderRequest request, UriComponentsBuilder uriBuilder) {
        OrderDto created = orderService.create(request);
        URI location = uriBuilder.path("/api/v1/orders/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    @PostMapping("/{id}/place")
    @PreAuthorize("hasRole('admin') or @orderAuthorization.ownsOrder(#id, authentication.token)")
    @Operation(summary = "Place the order (DRAFT → PLACED)")
    public OrderDto place(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return orderService.place(id, username(jwt));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Confirm the order (PLACED → CONFIRMED)")
    public OrderDto confirm(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal Jwt jwt) {
        return orderService.confirm(id, username(jwt), body != null ? body.get("reason") : null);
    }

    @PostMapping("/{id}/ship")
    @PreAuthorize("hasRole('admin')")
    @Operation(
            summary = "Request or retry shipment; returns 202 while inventory commits are pending")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Shipment completed or previously completed",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = OrderDto.class))),
        @ApiResponse(
                responseCode = "202",
                description = "Fulfillment pending; poll Location or retry after Retry-After",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = OrderDto.class)))
    })
    public ResponseEntity<OrderDto> ship(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal Jwt jwt) {
        OrderDto order =
                orderService.ship(id, username(jwt), body != null ? body.get("reason") : null);
        if ("FULFILLING".equals(order.status()) || "CANCELLING".equals(order.status())) {
            return ResponseEntity.accepted()
                    .location(URI.create("/api/v1/orders/" + id))
                    .header("Retry-After", "60")
                    .body(order);
        }
        return ResponseEntity.ok(order);
    }

    @PostMapping("/{id}/deliver")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Mark the order delivered (SHIPPED → DELIVERED)")
    public OrderDto deliver(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal Jwt jwt) {
        return orderService.deliver(id, username(jwt), body != null ? body.get("reason") : null);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('admin') or @orderAuthorization.ownsOrder(#id, authentication.token)")
    @Operation(summary = "Cancel the order; returns 202 while fulfillment compensation is pending")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Cancellation recorded; durable cleanup retained"),
        @ApiResponse(
                responseCode = "202",
                description = "Cancellation competes with pending shipment; poll Location")
    })
    public ResponseEntity<OrderDto> cancel(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal Jwt jwt) {
        var order =
                orderService.cancel(id, username(jwt), body != null ? body.get("reason") : null);
        if ("CANCELLING".equals(order.status())) {
            return ResponseEntity.accepted()
                    .location(URI.create("/api/v1/orders/" + id))
                    .header("Retry-After", "60")
                    .body(order);
        }
        return ResponseEntity.ok(order);
    }

    private String username(Jwt jwt) {
        String preferred = jwt.getClaimAsString("preferred_username");
        return preferred != null ? preferred : jwt.getSubject();
    }
}
