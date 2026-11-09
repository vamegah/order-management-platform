package com.example.omp.order.web;

import com.example.omp.order.dto.ReservationReferenceCensusPage;
import com.example.omp.order.dto.ReservationReferenceRepairRequest;
import com.example.omp.order.dto.ReservationReferenceRepairView;
import com.example.omp.order.service.OrderReservationRepairService;
import com.example.omp.order.service.ReservationReferenceCensus;
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
    private final ReservationReferenceCensus census;

    public OrderReservationRepairController(
            OrderReservationRepairService repairs, ReservationReferenceCensus census) {
        this.repairs = repairs;
        this.census = census;
    }

    @GetMapping("/census")
    public ReservationReferenceCensusPage census(
            @RequestParam(required = false) UUID afterLineId,
            @RequestParam(defaultValue = "50") int limit) {
        return census.scan(afterLineId, limit);
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
