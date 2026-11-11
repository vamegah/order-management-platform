package com.example.omp.inventory.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A reservation holds part of a StockLevel for an order.
 *
 * <p>Lifecycle is enforced by {@link #commit()}, {@link #release()}, and {@link #expire()}. Once a
 * reservation reaches a terminal status, no further transitions are allowed.
 *
 * <p>The quantity is not stored as a snapshot of available stock; it's the exact quantity the
 * reservation holds. StockLevel tracks the aggregate reserved count.
 */
@Entity
@Table(name = "reservation", schema = "inventory")
@EntityListeners(AuditingEntityListener.class)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Version private long version;

    @NotBlank
    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @NotBlank
    @Column(name = "warehouse_id", nullable = false, length = 50)
    private String warehouseId;

    @Positive
    @Column(name = "quantity", nullable = false)
    private int quantity;

    @NotNull
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.ACTIVE;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    protected Reservation() {}

    public Reservation(String sku, String warehouseId, int quantity, UUID orderId) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        this.sku = sku;
        this.warehouseId = warehouseId;
        this.quantity = quantity;
        this.orderId = orderId;
        this.status = ReservationStatus.ACTIVE;
    }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    /** Transitions ACTIVE → COMMITTED. */
    public void commit() {
        requireActive("commit");
        this.status = ReservationStatus.COMMITTED;
    }

    /** Transitions ACTIVE → RELEASED. */
    public void release() {
        requireActive("release");
        this.status = ReservationStatus.RELEASED;
    }

    /** Transitions ACTIVE → EXPIRED. Called by the expiration job. */
    public void expire() {
        requireActive("expire");
        this.status = ReservationStatus.EXPIRED;
    }

    public void compensateCommitted() {
        if (status == ReservationStatus.RESTOCKED) return;
        if (status != ReservationStatus.COMMITTED)
            throw new IllegalStateException("Only committed inventory can be restocked");
        status = ReservationStatus.RESTOCKED;
    }

    private void requireActive(String action) {
        if (this.status != ReservationStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot "
                            + action
                            + " reservation "
                            + id
                            + ": current status is "
                            + status
                            + " (expected ACTIVE)");
        }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getWarehouseId() {
        return warehouseId;
    }

    public int getQuantity() {
        return quantity;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Reservation other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Reservation{id="
                + id
                + ", sku='"
                + sku
                + "', qty="
                + quantity
                + ", status="
                + status
                + "}";
    }
}
