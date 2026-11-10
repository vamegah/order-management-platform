package com.example.omp.order.domain;

import com.example.omp.shared.domain.Money;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single line in an order.
 *
 * <p>Snapshots product details (SKU, name, unit price) at order time. The {@code productId}
 * references a product in the {@code products} schema, but is not a foreign key — products can be
 * deleted and orders must not break.
 *
 * <p>Line total = unit price × quantity. Recomputed by {@link Order#recalculateTotals}.
 */
@Entity
@Table(name = "order_line", schema = "orders")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @NotNull
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "reservation_id")
    private UUID reservationId;

    /** Original checkout intent, absent when historical provenance is unknown. */
    @Column(name = "reservation_warehouse_id", length = 64, updatable = false)
    private String reservationWarehouseId;

    public String getReservationWarehouseId() {
        return reservationWarehouseId;
    }

    public void bindReservation(UUID reservationId, String warehouseId) {
        Objects.requireNonNull(reservationId);
        if (warehouseId == null
                || warehouseId.isBlank()
                || warehouseId.length() > 64
                || !warehouseId.equals(warehouseId.strip()))
            throw new IllegalArgumentException("A bounded original warehouse is required");
        if ((this.reservationId != null && !this.reservationId.equals(reservationId))
                || (reservationWarehouseId != null && !reservationWarehouseId.equals(warehouseId)))
            throw new IllegalStateException("Original reservation provenance cannot be replaced");
        this.reservationId = reservationId;
        this.reservationWarehouseId = warehouseId;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public void setReservationId(UUID reservationId) {
        Objects.requireNonNull(reservationId);
        if (reservationWarehouseId != null && !reservationId.equals(this.reservationId))
            throw new IllegalStateException("Original reservation provenance cannot be replaced");
        this.reservationId = reservationId;
    }

    @NotBlank
    @Column(name = "product_sku", nullable = false, length = 64)
    private String productSku;

    @NotBlank
    @Column(name = "product_name", nullable = false, length = 300)
    private String productName;

    /** Unit price as a value object (amount + currency). */
    @NotNull
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(
                name = "amount",
                column =
                        @Column(
                                name = "unit_price_amount",
                                nullable = false,
                                precision = 19,
                                scale = 4)),
        @AttributeOverride(
                name = "currency",
                column =
                        @Column(
                                name = "unit_price_currency",
                                nullable = false,
                                length = 3,
                                columnDefinition = "bpchar(3)"))
    })
    private Money unitPrice;

    @Positive
    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** Denormalized: unit price × quantity. */
    @NotNull
    @Column(name = "line_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void initializeTimestamps() {
        createdAt = Instant.now();
    }

    protected OrderLine() {}

    public OrderLine(
            UUID productId, String productSku, String productName, Money unitPrice, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        this.productId = productId;
        this.productSku = productSku;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.lineTotal = unitPrice.getAmount().multiply(BigDecimal.valueOf(quantity));
    }

    /** The line total as a value object. */
    public Money lineTotal() {
        return Money.of(lineTotal, unitPrice.getCurrency());
    }

    // Getters / setters
    public UUID getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    void setOrder(Order order) {
        this.order = order;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getProductSku() {
        return productSku;
    }

    public String getProductName() {
        return productName;
    }

    public Money getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
        this.quantity = quantity;
        this.lineTotal = unitPrice.getAmount().multiply(BigDecimal.valueOf(quantity));
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderLine other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "OrderLine{sku='" + productSku + "', qty=" + quantity + "}";
    }
}
