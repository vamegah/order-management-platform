package com.example.omp.inventory.grpc.converter;

import com.example.omp.inventory.domain.Reservation;
import com.example.omp.inventory.domain.ReservationStatus;
import com.example.omp.inventory.domain.StockLevel;

/**
 * Conversions between domain entities and gRPC proto messages.
 *
 * <p>All methods are static — the conversions are stateless.
 *
 * <p><b>Naming collision:</b> the domain entity and the proto message for stock have the same
 * simple name ({@code StockLevel}). We disambiguate with fully-qualified names in method signatures
 * and rely on Java's package resolution for the rest.
 */
public final class ProtoConverters {

    private ProtoConverters() {
        // Utility class.
    }

    // =========================================================================
    // StockLevel
    // =========================================================================

    /** Converts a {@link StockLevel} entity to its proto message. */
    public static com.example.omp.proto.inventory.StockLevel toProto(StockLevel stock) {
        return com.example.omp.proto.inventory.StockLevel.newBuilder()
                .setSku(stock.getSku())
                .setWarehouseId(stock.getWarehouseId())
                .setQuantityOnHand(stock.getQuantityOnHand())
                .setQuantityReserved(stock.getQuantityReserved())
                .setQuantityAvailable(stock.getQuantityAvailable())
                .setLastUpdatedEpochMillis(stock.getLastUpdated().toEpochMilli())
                .build();
    }

    // =========================================================================
    // Reservation
    // =========================================================================

    /** Converts a {@link Reservation} entity to its proto message. */
    public static com.example.omp.proto.inventory.Reservation toProto(Reservation reservation) {
        return com.example.omp.proto.inventory.Reservation.newBuilder()
                .setId(reservation.getId().toString())
                .setSku(reservation.getSku())
                .setWarehouseId(reservation.getWarehouseId())
                .setQuantity(reservation.getQuantity())
                .setOrderId(reservation.getOrderId().toString())
                .setStatus(toProtoStatus(reservation.getStatus()))
                .setCreatedAtEpochMillis(reservation.getCreatedAt().toEpochMilli())
                .setUpdatedAtEpochMillis(reservation.getUpdatedAt().toEpochMilli())
                .build();
    }

    /**
     * Converts the domain's {@link ReservationStatus} enum to the proto's {@code ReservationStatus}
     * enum.
     *
     * <p>Why separate enums? The proto enum has a different naming convention (prefixes like
     * RESERVATION_STATUS_) to avoid collisions with other enums in the same package. The domain
     * enum is idiomatic Java (no prefixes).
     */
    private static com.example.omp.proto.inventory.ReservationStatus toProtoStatus(
            ReservationStatus status) {
        return switch (status) {
            case ACTIVE ->
                    com.example.omp.proto.inventory.ReservationStatus.RESERVATION_STATUS_ACTIVE;
            case COMMITTED ->
                    com.example.omp.proto.inventory.ReservationStatus.RESERVATION_STATUS_COMMITTED;
            case RELEASED ->
                    com.example.omp.proto.inventory.ReservationStatus.RESERVATION_STATUS_RELEASED;
            case EXPIRED ->
                    com.example.omp.proto.inventory.ReservationStatus.RESERVATION_STATUS_EXPIRED;
            case RESTOCKED ->
                    com.example.omp.proto.inventory.ReservationStatus.RESERVATION_STATUS_RESTOCKED;
        };
    }
}
