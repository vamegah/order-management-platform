package com.example.omp.order.domain;

import com.example.omp.shared.domain.Money;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An order. Aggregate root for the ordering domain.
 *
 * <p>An order owns its lines and its status history. Lines are immutable once the order leaves
 * {@link OrderStatus#DRAFT}. Status transitions follow a state machine enforced by {@link
 * #transitionTo}.
 *
 * <p>Snapshots: order lines snapshot product details (SKU, name, unit price) at order time. The
 * shipping address is a snapshot copied from the customer at order time. Changing a product or a
 * customer's address later does not affect existing orders.
 *
 * <p>Maps to {@code orders.purchase_order} (the table name avoids the SQL reserved word {@code
 * order}).
 */
@Entity
@Table(name = "purchase_order", schema = "orders")
public class Order {

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Human-readable identifier (ORD-YYYY-NNNNNNN). Unique. */
    @NotNull @Embedded private OrderNumber orderNumber;

    /** Customer reference. No FK — customers live in another schema. */
    @NotNull
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /** Snapshot of the customer's email at order time. */
    @NotBlank
    @Column(name = "customer_email", nullable = false, columnDefinition = "citext")
    private String customerEmail;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.DRAFT;

    // ---- Totals (Money) -----------------------------------------------------

    @NotNull
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(
                name = "amount",
                column =
                        @Column(
                                name = "subtotal_amount",
                                nullable = false,
                                precision = 19,
                                scale = 4)),
        @AttributeOverride(
                name = "currency",
                column =
                        @Column(
                                name = "currency",
                                nullable = false,
                                length = 3,
                                columnDefinition = "bpchar(3)"))
    })
    private Money subtotal;

    @NotNull
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(
                name = "amount",
                column = @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)),
        @AttributeOverride(
                name = "currency",
                column =
                        @Column(
                                name = "currency",
                                nullable = false,
                                length = 3,
                                columnDefinition = "bpchar(3)",
                                insertable = false,
                                updatable = false))
    })
    private Money tax;

    @NotNull
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(
                name = "amount",
                column =
                        @Column(
                                name = "shipping_amount",
                                nullable = false,
                                precision = 19,
                                scale = 4)),
        @AttributeOverride(
                name = "currency",
                column =
                        @Column(
                                name = "currency",
                                nullable = false,
                                length = 3,
                                columnDefinition = "bpchar(3)",
                                insertable = false,
                                updatable = false))
    })
    private Money shipping;

    @NotNull
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(
                name = "amount",
                column =
                        @Column(
                                name = "total_amount",
                                nullable = false,
                                precision = 19,
                                scale = 4)),
        @AttributeOverride(
                name = "currency",
                column =
                        @Column(
                                name = "currency",
                                nullable = false,
                                length = 3,
                                columnDefinition = "bpchar(3)",
                                insertable = false,
                                updatable = false))
    })
    private Money total;

    /** Shipping address snapshot. */
    @NotNull @Embedded private ShippingAddress shippingAddress;

    @Column(name = "placed_at")
    private Instant placedAt;

    /**
     * Payment intent ID from payment-service. Set when the order is created; used later to cancel
     * or refund the payment.
     *
     * <p>Cross-service reference (no FK). Nullable for DRAFT orders that predate the payment link
     * (Phase 3 makes this non-null in practice).
     */
    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "notes")
    private String notes;

    /** Order lines. Owned; adding/removing cascades. */
    @OneToMany(
            mappedBy = "order",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<OrderLine> lines = new ArrayList<>();

    /** Status history. Append-only; never removed by the application. */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("changedAt ASC")
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    @PrePersist
    void initializeTimestamps() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
    }

    protected Order() {}

    /**
     * Creates a new order in DRAFT status.
     *
     * @param orderNumber the assigned business identifier
     * @param customerId reference to the customer
     * @param customerEmail email snapshot
     * @param currency the order's currency (ISO 4217)
     * @param shippingAddress shipping address snapshot
     */
    public Order(
            UUID id,
            OrderNumber orderNumber,
            UUID customerId,
            String customerEmail,
            String currency,
            ShippingAddress shippingAddress,
            UUID paymentId) {
        this.id = Objects.requireNonNull(id, "Order ID required");
        this.orderNumber = orderNumber;
        this.customerId = customerId;
        this.customerEmail = customerEmail;
        this.shippingAddress = shippingAddress;
        this.paymentId = paymentId;
        this.status = OrderStatus.DRAFT;
        this.subtotal = Money.zero(currency);
        this.tax = Money.zero(currency);
        this.shipping = Money.zero(currency);
        this.total = Money.zero(currency);
        appendHistory(null, OrderStatus.DRAFT, "Order created", "system");
    }

    // -------------------------------------------------------------------------
    // Line operations — only allowed in DRAFT
    // -------------------------------------------------------------------------

    /** Adds a line to the order. */
    public void addLine(OrderLine line) {
        requireStatus(OrderStatus.DRAFT, "add lines");
        lines.add(line);
        line.setOrder(this);
        recalculateTotals();
        line.setOrder(this); // ensure ownership after recalc
    }

    /** Removes a line from the order. Cannot remove the last line. */
    public void removeLine(OrderLine line) {
        requireStatus(OrderStatus.DRAFT, "remove lines");
        if (lines.size() <= 1) {
            throw new IllegalStateException(
                    "Cannot remove the last line of an order. Cancel the order instead.");
        }
        lines.remove(line);
        line.setOrder(null);
        recalculateTotals();
    }

    /** Recomputes subtotal and total from lines. Idempotent. */
    public void recalculateTotals() {
        Money newSubtotal = Money.zero(subtotal.getCurrencyCode());
        for (OrderLine line : lines) {
            newSubtotal = newSubtotal.add(line.lineTotal());
        }
        this.subtotal = newSubtotal;
        this.total = newSubtotal.add(tax).add(shipping);
    }

    // -------------------------------------------------------------------------
    // State machine
    // -------------------------------------------------------------------------

    /**
     * Places the order (DRAFT → PLACED). Records the transition and sets {@code placedAt}.
     *
     * @param by who triggered the transition (usually the customer)
     */
    public void place(String by) {
        requireReservationReferences();
        transitionTo(OrderStatus.PLACED, "Checkout", by);
        this.placedAt = Instant.now();
    }

    /** Confirms the order (PLACED → CONFIRMED). Usually payment success. */
    public void confirm(String by, String reason) {
        requireReservationReferences();
        transitionTo(OrderStatus.CONFIRMED, reason, by);
    }

    /** Fences cancellation before any inventory commit is attempted. */
    public void requestShipment(String by, String reason) {
        requireReservationReferences();
        transitionTo(OrderStatus.FULFILLING, reason, by);
    }

    /** Marks the order shipped (FULFILLING → SHIPPED) after durable inventory acknowledgement. */
    public void ship(String by, String reason) {
        requireReservationReferences();
        transitionTo(OrderStatus.SHIPPED, reason, by);
    }

    /** Marks the order delivered (SHIPPED → DELIVERED). */
    public void deliver(String by, String reason) {
        requireReservationReferences();
        transitionTo(OrderStatus.DELIVERED, reason, by);
    }

    /** Cancels the order. Allowed from DRAFT, PLACED, and CONFIRMED. */
    public void cancel(String by, String reason) {
        transitionTo(OrderStatus.CANCELLED, reason, by);
    }

    private void transitionTo(OrderStatus next, String reason, String by) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal status transition: " + status + " → " + next);
        }
        OrderStatus previous = this.status;
        this.status = next;
        appendHistory(previous, next, reason, by);
    }

    private void appendHistory(OrderStatus from, OrderStatus to, String reason, String by) {
        OrderStatusHistory history = new OrderStatusHistory(from, to, reason, by);
        history.setOrder(this);
        statusHistory.add(history);
    }

    private void requireStatus(OrderStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Cannot "
                            + action
                            + " when order is in status "
                            + status
                            + " (expected "
                            + expected
                            + ")");
        }
    }

    private void requireAtLeastOneLine() {
        if (lines.isEmpty()) {
            throw new IllegalStateException("Cannot place an order with no lines");
        }
    }

    /** Local reference integrity; remote ownership is verified by checkout or audited repair. */
    private void requireReservationReferences() {
        requireAtLeastOneLine();
        if (lines.size() > 100
                || lines.stream().anyMatch(line -> line.getReservationId() == null)
                || lines.stream().map(OrderLine::getReservationId).distinct().count()
                        != lines.size()) {
            throw new IllegalStateException(
                    "Every order line requires a unique inventory reservation");
        }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public OrderNumber getOrderNumber() {
        return orderNumber;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Money getSubtotal() {
        return subtotal;
    }

    public Money getTax() {
        return tax;
    }

    public Money getShipping() {
        return shipping;
    }

    public Money getTotal() {
        return total;
    }

    public ShippingAddress getShippingAddress() {
        return shippingAddress;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public String getNotes() {
        return notes;
    }

    public List<OrderLine> getLines() {
        return lines;
    }

    public List<OrderStatusHistory> getStatusHistory() {
        return statusHistory;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public long getVersion() {
        return version;
    }

    // -------------------------------------------------------------------------
    // Setters (limited — most fields are managed by domain behavior)
    // -------------------------------------------------------------------------

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public void setTax(Money tax) {
        this.tax = tax;
        recalculateTotals();
    }

    public void setShipping(Money shipping) {
        this.shipping = shipping;
        recalculateTotals();
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Order{id=" + id + ", number=" + orderNumber + ", status=" + status + "}";
    }
}
