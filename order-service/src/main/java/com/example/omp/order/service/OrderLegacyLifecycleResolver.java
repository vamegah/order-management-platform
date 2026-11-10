package com.example.omp.order.service;

import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.domain.Order;
import com.example.omp.order.domain.OrderStatus;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderLegacyLifecycleResolver {
    private final OrderLegacyLifecycleStore store;

    public OrderLegacyLifecycleResolver(OrderLegacyLifecycleStore store) {
        this.store = store;
    }

    public void finishCancellation(OrderLifecycleStore.Claim claim) {
        store.finishCancellation(claim);
    }

    public void resolve(
            OrderLifecycleStore.Claim claim, Order order, List<ReservationDto> discovered) {
        OrderLifecycleCoordinator.validateReservations(order.getId(), discovered);
        Map<UUID, ReservationDto> verified = new HashMap<>();
        Set<UUID> used = new HashSet<>();
        boolean irrecoverable = order.getLines().isEmpty();
        for (var line : order.getLines()) {
            var matching =
                    discovered.stream()
                            .filter(
                                    r ->
                                            line.getProductSku().equals(r.sku())
                                                    && line.getQuantity() == r.quantity()
                                                    && (line.getReservationWarehouseId() == null
                                                            || line.getReservationWarehouseId()
                                                                    .equals(r.warehouseId())))
                            .toList();
            // Prefer a valid stored original. A known terminal original cannot be replaced by
            // a later hold. A foreign/swapped pointer can be repaired from unique owned proof.
            var pointed =
                    matching.stream()
                            .filter(r -> r.id().equals(line.getReservationId()))
                            .findFirst();
            var candidates =
                    (pointed.isPresent() ? pointed.stream() : matching.stream())
                            .filter(
                                    r ->
                                            Set.of(
                                                            "RESERVATION_STATUS_ACTIVE",
                                                            "RESERVATION_STATUS_COMMITTED")
                                                    .contains(r.status()))
                            .filter(
                                    r ->
                                            order.getStatus() != OrderStatus.DELIVERED
                                                    || "RESERVATION_STATUS_COMMITTED"
                                                            .equals(r.status()))
                            .toList();
            if (candidates.size() == 1 && used.add(candidates.getFirst().id())) {
                verified.put(line.getId(), candidates.getFirst());
            } else {
                irrecoverable = true;
            }
        }
        if (order.getStatus() == OrderStatus.CANCELLED)
            irrecoverable = verified.size() != order.getLines().size();
        store.resolve(claim, order, verified, irrecoverable);
    }
}
