package com.example.omp.order.web;

import com.example.omp.order.dto.ReservationReferenceRepairRequest;
import com.example.omp.order.dto.ReservationReferenceRepairView;
import com.example.omp.order.service.OrderReservationRepairService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/order-reservation-repairs")
@PreAuthorize("hasRole('admin')")
public class OrderReservationRepairController {
    private final OrderReservationRepairService repairs;

    public OrderReservationRepairController(OrderReservationRepairService repairs) {
        this.repairs = repairs;
    }

    @GetMapping("/{orderId}")
    public ReservationReferenceRepairView inspect(@PathVariable UUID orderId) {
        return repairs.inspect(orderId);
    }

    @PostMapping("/{orderId}")
    public ReservationReferenceRepairView restore(
            @PathVariable UUID orderId,
            @Valid @RequestBody ReservationReferenceRepairRequest request,
            Authentication operator) {
        return repairs.restore(orderId, request, operator == null ? null : operator.getName());
    }
}
