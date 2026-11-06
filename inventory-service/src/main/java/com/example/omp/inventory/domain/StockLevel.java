package com.example.omp.inventory.domain;

import com.example.omp.inventory.error.InsufficientStockException;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Stock level for one SKU in one warehouse.
 *
 * <p>Two entities in the inventory domain:
 *
 * <ul>
 *   <li>{@code StockLevel} — the current physical and reserved counts.
 *   <li>{@link Reservation} — a hold on part of a StockLevel for an order.
 * </ul>
 *
 * <p><b>Optimistic locking:</b> {@link #version} is the JPA-managed version field. Concurrent
 * transactions that read the same StockLevel will conflict at commit; the second one fails with
 * {@code OptimisticLockingFailureException}. The service layer retries (see ReservationHandler).
 *
 * <p><b>Invariants:</b>
 *
 * <ul>
 *   <li>{@code quantityOnHand >= 0}
 *   <li>{@code quantityReserved >= 0}
 *   <li>{@code quantityReserved <= quantityOnHand}
 *   <li>{@code quantityAvailable = quantityOnHand - quantityReserved}
 * </ul>
 */
@Entity
@Table(
        name = "stock_level",
        schema = "inventory",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_stock_level_sku_warehouse",
                        columnNames = {"sku", "warehouse_id"}))
@EntityListeners(AuditingEntityListener.class)
public class StockLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank
    @Size(max = 64)
    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @NotBlank
    @Size(max = 50)
    @Column(name = "warehouse_id", nullable = false, length = 50)
    private String warehouseId;

    @PositiveOrZero
    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @PositiveOrZero
    @Column(name = "quantity_reserved", nullable = false)
    private int quantityReserved;

    /**
     * Optimistic locking version. JPA increments this on every save. Two transactions that read the
     * same version will conflict at commit.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    protected StockLevel() {
        // JPA only
    }

    public StockLevel(String sku, String warehouseId, int initialQuantity) {
        if (initialQuantity < 0) {
            throw new IllegalArgumentException("Initial quantity cannot be negative");
        }
        this.sku = sku;
        this.warehouseId = warehouseId;
        this.quantityOnHand = initialQuantity;
        this.quantityReserved = 0;
    }

    // -------------------------------------------------------------------------
    // Domain behavior
    // -------------------------------------------------------------------------

    /**
     * Reserves the given quantity.
     *
     * @throws InsufficientStockException if not enough is available
     */
    public void reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        int available = getQuantityAvailable();
        if (available < quantity) {
            throw new InsufficientStockException(
                    "Insufficient stock for SKU "
                            + sku
                            + " in "
                            + warehouseId
                            + ": requested "
                            + quantity
                            + ", available "
                            + available);
        }
        this.quantityReserved += quantity;
    }

    /**
     * Releases a reservation back to available stock.
     *
     * @throws IllegalStateException if releasing more than is reserved
     */
    public void release(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (this.quantityReserved < quantity) {
            throw new IllegalStateException(
                    "Cannot release "
                            + quantity
                            + " units; only "
                            + this.quantityReserved
                            + " reserved");
        }
        this.quantityReserved -= quantity;
    }

    /**
     * Commits a reservation: decrements both on-hand and reserved counts. Called when an order
     * ships.
     */
    public void commit(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (this.quantityReserved < quantity) {
            throw new IllegalStateException(
                    "Cannot commit "
                            + quantity
                            + " units; only "
                            + this.quantityReserved
                            + " reserved");
        }
        this.quantityOnHand -= quantity;
        this.quantityReserved -= quantity;
    }

    /** Increases on-hand quantity. Called by a future restock endpoint. */
    public void restock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Restock quantity must be positive");
        }
        this.quantityOnHand += quantity;
    }

    // -------------------------------------------------------------------------
    // Derived properties
    // -------------------------------------------------------------------------

    public int getQuantityAvailable() {
        return quantityOnHand - quantityReserved;
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

    public int getQuantityOnHand() {
        return quantityOnHand;
    }

    public int getQuantityReserved() {
        return quantityReserved;
    }

    public long getVersion() {
        return version;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StockLevel other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "StockLevel{sku='"
                + sku
                + "', warehouse='"
                + warehouseId
                + "', onHand="
                + quantityOnHand
                + ", reserved="
                + quantityReserved
                + "}";
    }
}
