package com.example.omp.order.service;

import com.example.omp.order.clients.CustomerClient;
import com.example.omp.order.clients.ProductClient;
import com.example.omp.order.clients.dto.CustomerDto;
import com.example.omp.order.clients.dto.ProductDto;
import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.PaymentClient;
import com.example.omp.order.clients.grpc.dto.PaymentIntentDto;
import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.domain.*;
import com.example.omp.order.dto.*;
import com.example.omp.order.error.*;
import com.example.omp.order.events.OrderEventPublisher;
import com.example.omp.order.repository.OrderRepository;
import com.example.omp.order.repository.OrderSpecifications;
import com.example.omp.shared.domain.Money;
import com.example.omp.shared.web.PageResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrating service for orders.
 *
 * <p>Distinguishes between:
 *
 * <ul>
 *   <li><b>External calls</b> — to product-service and customer-service via Feign. These happen
 *       outside transactions to avoid holding DB connections while waiting for HTTP.
 *   <li><b>Transactional work</b> — delegated to {@link OrderTransactionService}.
 * </ul>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderTransactionService tx;
    private final ProductClient productClient;
    private final CustomerClient customerClient;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderCleanupQueue cleanup;
    private final CheckoutJournal journal;
    private final OrderFulfillmentStore fulfillmentStore;
    private final OrderFulfillmentQueue fulfillment;

    public OrderService(
            OrderRepository orderRepository,
            OrderTransactionService tx,
            ProductClient productClient,
            CustomerClient customerClient,
            OrderEventPublisher orderEventPublisher,
            InventoryClient inventoryClient,
            PaymentClient paymentClient,
            OrderCleanupQueue cleanup,
            CheckoutJournal journal,
            OrderFulfillmentStore fulfillmentStore,
            OrderFulfillmentQueue fulfillment) {
        this.orderRepository = orderRepository;
        this.tx = tx;
        this.productClient = productClient;
        this.customerClient = customerClient;
        this.orderEventPublisher = orderEventPublisher;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.cleanup = cleanup;
        this.journal = journal;
        this.fulfillmentStore = fulfillmentStore;
        this.fulfillment = fulfillment;
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<OrderDto> list(OrderFilter filter, Pageable pageable) {
        Specification<Order> spec = OrderSpecifications.fromFilter(filter);
        Page<Order> page = orderRepository.findAll(spec, pageable);
        return PageResponse.from(page, OrderMapper::toDto);
    }

    @Transactional(readOnly = true)
    public OrderDto getById(UUID id, boolean includeHistory) {
        Order order = tx.findByIdFull(id);
        return OrderMapper.toDto(order, includeHistory);
    }

    @Transactional(readOnly = true)
    public OrderDto getByNumber(String orderNumber, boolean includeHistory) {
        OrderNumber number = OrderNumber.of(orderNumber);
        Order order =
                orderRepository
                        .findByOrderNumber(number)
                        .orElseThrow(() -> new OrderNotFoundException(orderNumber));
        return OrderMapper.toDto(order, includeHistory);
    }

    /**
     * Creates a new DRAFT order.
     *
     * <p><b>Orchestration flow:</b>
     *
     * <ol>
     *   <li>Fetch customer (REST, via Feign → customer-service).
     *   <li>Fetch each product (REST, via Feign → product-service).
     *   <li>Generate the order ID upfront (used for idempotency keys).
     *   <li>Reserve stock for each line (gRPC → inventory-service).
     *   <li>Create a payment intent for the total (gRPC → payment-service).
     *   <li>Save the order in a transaction ({@link OrderTransactionService}).
     * </ol>
     *
     * <p><b>Compensation:</b> steps 4 and 5 make state changes in downstream services. If a later
     * step fails, the earlier changes are compensated (released reservations, cancelled payment
     * intent) on a best-effort basis. Failures during compensation are logged but not re-thrown —
     * the caller sees the original failure, and Phase 7's cleanup jobs will handle any orphaned
     * state.
     *
     * <p><b>Why generate the order ID upfront?</b> The ID is used as part of the idempotency key
     * for downstream calls ({@code "order-<id>-payment"}). If we let JPA generate it at save time,
     * the ID wouldn't exist when we made the gRPC calls.
     *
     * <p><b>Transaction boundaries:</b> this method is NOT {@code @Transactional}. The gRPC calls
     * happen outside any DB transaction. Journal updates and step 6 run in short transactions
     * ({@code tx.create} atomically accepts the journal). This is the same pattern established in
     * Phase 1 for REST calls — see the class-level Javadoc on the transaction boundary.
     */
    public OrderDto create(CreateOrderRequest request) {
        // Step 1: fetch customer (REST)
        CustomerDto customer = fetchCustomer(request.customerId());

        // Step 2: fetch products (REST) and validate
        List<OrderTransactionService.OrderLineDraft> drafts = new ArrayList<>();
        java.util.Set<String> skus = new java.util.HashSet<>();
        String orderCurrency = null;
        BigDecimal subtotal = BigDecimal.ZERO;

        for (CreateOrderRequest.LineRequest line : request.lines()) {
            ProductDto product = fetchProduct(line.productId());
            if (!skus.add(product.sku())) {
                throw new InvalidOrderDataException(
                        "Combine duplicate SKU quantities into one order line");
            }
            Money unitPrice =
                    Money.of(
                            product.price().amount(),
                            Currency.getInstance(product.price().currency()));

            if (orderCurrency == null) {
                orderCurrency = unitPrice.getCurrencyCode();
            } else if (!orderCurrency.equals(unitPrice.getCurrencyCode())) {
                throw new InvalidOrderDataException(
                        "All lines must be in the same currency. Order uses "
                                + orderCurrency
                                + ", line uses "
                                + unitPrice.getCurrencyCode());
            }

            drafts.add(
                    new OrderTransactionService.OrderLineDraft(
                            product.id(),
                            product.sku(),
                            product.name(),
                            unitPrice,
                            line.quantity()));

            subtotal =
                    subtotal.add(
                            unitPrice.getAmount().multiply(BigDecimal.valueOf(line.quantity())));
        }

        // Step 3: extract shipping address from request
        ShippingAddress shippingAddress =
                new ShippingAddress(
                        request.shippingAddress().line1(),
                        request.shippingAddress().line2(),
                        request.shippingAddress().city(),
                        request.shippingAddress().state(),
                        request.shippingAddress().postalCode(),
                        request.shippingAddress().country());

        // Step 4: generate order ID upfront
        UUID orderId = UUID.randomUUID();
        journal.start(orderId);

        // Steps 5-7: reservations, payment intent, save — with compensation
        List<ReservationDto> reservations = new ArrayList<>();
        PaymentIntentDto payment = null;
        boolean accepted = false;

        try {
            // Step 5: reserve stock (gRPC)
            for (OrderTransactionService.OrderLineDraft draft : drafts) {
                ReservationDto reservation =
                        inventoryClient.reserve(orderId, draft.sku(), "WH-1", draft.quantity());
                if (reservations.stream().anyMatch(known -> known.id().equals(reservation.id())))
                    throw new com.example.omp.order.error.DownstreamServiceException(
                            "inventory-service", 502, "Duplicate checkout reservation identity");
                reservations.add(reservation);
            }

            log.debug("Reserved {} lines for order {}", reservations.size(), orderId);

            // Step 6: create payment intent (gRPC)
            payment =
                    paymentClient.createIntent(
                            orderId,
                            customer.id(),
                            subtotal.toPlainString(),
                            orderCurrency,
                            "order-" + orderId + "-payment");

            log.debug("Created payment intent {} for order {}", payment.id(), orderId);

            // Step 7: save order in a short transaction
            Order saved =
                    tx.create(
                            orderId,
                            customer,
                            shippingAddress,
                            drafts,
                            payment.id(),
                            reservations.stream()
                                    .map(
                                            r ->
                                                    new OrderTransactionService.ReservedLine(
                                                            r.id(), r.warehouseId()))
                                    .toList());

            accepted = true;
            log.info(
                    "Created order id={} number={} reservations={} payment={}",
                    saved.getId(),
                    saved.getOrderNumber(),
                    reservations.size(),
                    payment.id());

            return OrderMapper.toDto(saved);

        } catch (Exception e) {
            if (accepted) throw e;
            try {
                journal.fail(orderId);
            } catch (RuntimeException journalFailure) {
                log.error("Checkout failure marker pending: order={}", orderId);
            }
            log.warn(
                    "Order creation failed; compensating. orderId={} error={}",
                    orderId,
                    e.getMessage());

            try {
                if (payment != null) cleanup.enqueue(orderId, "PAYMENT", payment.id());
                for (ReservationDto reservation : reservations) {
                    cleanup.enqueue(orderId, "RESERVATION", reservation.id());
                }
                cleanup.retryOrder(orderId, 100);
            } catch (RuntimeException cleanupFailure) {
                log.error(
                        "Order compensation requires reconciliation: order={}",
                        orderId,
                        cleanupFailure);
            }

            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    public OrderDto place(UUID id, String by) {
        return transition(
                id,
                order -> {
                    order.place(by);
                    orderEventPublisher.publishPlaced(order, by);
                });
    }

    public OrderDto confirm(UUID id, String by, String reason) {
        return paidTransition(
                id,
                OrderStatus.PLACED,
                order -> {
                    order.confirm(by, reason != null ? reason : "Confirmed");
                    orderEventPublisher.publishConfirmed(order, by, reason);
                });
    }

    public OrderDto ship(UUID id, String by, String reason) {
        Order snapshot = tx.findByIdFull(id);
        if (snapshot.getStatus() == OrderStatus.SHIPPED
                || snapshot.getStatus() == OrderStatus.DELIVERED)
            return OrderMapper.toDto(snapshot);
        if (snapshot.getStatus() != OrderStatus.FULFILLING) {
            paidTransition(
                    snapshot,
                    OrderStatus.CONFIRMED,
                    order -> {
                        String explanation = reason != null ? reason : "Shipment requested";
                        order.requestShipment(by, explanation);
                        fulfillmentStore.enqueue(order, by, explanation);
                    });
        }
        // The intent and cancellation fence have committed before the first inventory mutation.
        try {
            fulfillment.retryOrder(id, 100);
        } catch (RuntimeException failure) {
            log.warn("Shipment remains pending: order={}", id);
        }
        return OrderMapper.toDto(tx.findByIdFull(id));
    }

    public OrderDto deliver(UUID id, String by, String reason) {
        return transition(
                id,
                order -> {
                    order.deliver(by, reason != null ? reason : "Delivered");
                    orderEventPublisher.publishDelivered(order, by, reason);
                });
    }

    /**
     * Cancels an order.
     *
     * <p><b>Two-phase operation:</b>
     *
     * <ol>
     *   <li>State transition: DRAFT/PLACED/CONFIRMED → CANCELLED. This is a local DB write and
     *       happens in a short transaction.
     *   <li>Downstream cleanup: release any inventory reservations, cancel the payment intent.
     *       These are gRPC calls, made AFTER the local transaction commits.
     * </ol>
     *
     * <p><b>Why cleanup after commit?</b> If we cleaned up downstream state before the local
     * commit, and the commit then failed, we'd have released resources for an order that's still
     * active. Cleanup-after-commit means: the order is definitively cancelled locally, and then we
     * undo the downstream effects.
     *
     * <p><b>Idempotency:</b> a second cancel call is a no-op at the local level (the order is
     * already CANCELLED). Downstream cleanup calls are also idempotent — inventory.release on an
     * already-released reservation is a no-op, payment.cancel on an already-cancelled payment is a
     * no-op.
     *
     * <p><b>Cleanup failures:</b> if a downstream cleanup call fails, we log it but don't fail the
     * whole cancel operation. The local state says "cancelled" — that's what the user sees.
     * Retrying cancellation retries downstream cleanup without duplicating the state history.
     * Cleanup tasks are retained durably; authenticated operator retries are available.
     */
    public OrderDto cancel(UUID id, String by, String reason) {
        // -------------------------------------------------------------------------
        // Step 1: state transition (local transaction).
        // -------------------------------------------------------------------------
        Order cancelled;
        try {
            cancelled =
                    tx.findAndApply(
                            id,
                            o -> {
                                if (o.getStatus() != OrderStatus.CANCELLED) {
                                    o.cancel(by, reason != null ? reason : "Cancelled");
                                    orderEventPublisher.publishCancelled(o, by, reason);
                                }
                                for (OrderLine line : o.getLines()) {
                                    if (line.getReservationId() != null) {
                                        cleanup.enqueue(
                                                o.getId(), "RESERVATION", line.getReservationId());
                                    }
                                }
                                if (o.getPaymentId() != null)
                                    cleanup.enqueue(o.getId(), "PAYMENT", o.getPaymentId());
                            });
        } catch (IllegalStateException e) {
            // Domain state machine error → 409
            throw new InvalidOrderStateException(e.getMessage());
        }

        log.info(
                "Cancelled order id={} number={} by={}",
                cancelled.getId(),
                cancelled.getOrderNumber(),
                by);

        // -------------------------------------------------------------------------
        // Step 2: downstream cleanup (gRPC, outside transaction).
        // -------------------------------------------------------------------------
        try {
            cleanup.retryOrder(cancelled.getId(), 100);
        } catch (RuntimeException failure) {
            log.error(
                    "Cancelled order cleanup remains pending: order={}",
                    cancelled.getId(),
                    failure);
        }

        return OrderMapper.toDto(cancelled);
    }

    public OrderDto updateNotes(UUID id, String notes) {
        return transition(id, o -> o.setNotes(notes));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private OrderDto paidTransition(
            UUID id, OrderStatus expected, java.util.function.Consumer<Order> mutation) {
        return paidTransition(tx.findByIdFull(id), expected, mutation);
    }

    private OrderDto paidTransition(
            Order snapshot, OrderStatus expected, java.util.function.Consumer<Order> mutation) {
        // The payment RPC is deliberately outside the local mutation transaction.
        if (snapshot.getStatus() != expected || snapshot.getPaymentId() == null) {
            throw new InvalidOrderStateException(
                    "Order requires " + expected + " state and a linked payment");
        }
        long version = snapshot.getVersion();
        UUID paymentId = snapshot.getPaymentId();
        PaymentIntentDto payment = paymentClient.get(paymentId);
        OrderPaymentPolicy.requireMatchingPayment(snapshot, payment);
        return transition(
                snapshot.getId(),
                order -> {
                    if (order.getVersion() != version || !paymentId.equals(order.getPaymentId())) {
                        throw new InvalidOrderStateException(
                                "Order changed during payment verification. Reload and retry.");
                    }
                    OrderPaymentPolicy.requireMatchingPayment(order, payment);
                    mutation.accept(order);
                });
    }

    private OrderDto transition(UUID id, java.util.function.Consumer<Order> mutation) {
        try {
            Order order = tx.findAndApply(id, mutation);
            return OrderMapper.toDto(order);
        } catch (IllegalStateException e) {
            // Domain state machine error → 409
            throw new InvalidOrderStateException(e.getMessage());
        }
    }

    private CustomerDto fetchCustomer(UUID id) {
        try {
            return customerClient.getCustomer(id);
        } catch (CallNotPermittedException e) {
            throw new DownstreamServiceException("customer-service", 503, "Circuit breaker open");
        } catch (InvalidOrderDataException e) {
            // Feign decoder converts 404 → InvalidOrderDataException for customer lookups
            throw new CustomerNotFoundException(id);
        }
    }

    private ProductDto fetchProduct(UUID id) {
        try {
            return productClient.getProduct(id);
        } catch (CallNotPermittedException e) {
            throw new DownstreamServiceException("product-service", 503, "Circuit breaker open");
        } catch (ProductUnavailableException e) {
            throw e; // already a domain error
        }
    }
}
