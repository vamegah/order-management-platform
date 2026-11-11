package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.omp.order.clients.CustomerClient;
import com.example.omp.order.clients.ProductClient;
import com.example.omp.order.clients.dto.CustomerDto;
import com.example.omp.order.clients.dto.ProductDto;
import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.PaymentClient;
import com.example.omp.order.domain.Order;
import com.example.omp.order.dto.CreateOrderRequest;
import com.example.omp.order.events.OrderEventPublisher;
import com.example.omp.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Test
    void cancellationDurablyQueuesResourcesWithoutDuplicatingHistory() {
        UUID id = UUID.randomUUID();
        UUID firstReservation = UUID.randomUUID();
        UUID secondReservation = UUID.randomUUID();
        var order =
                new Order(
                        id,
                        com.example.omp.order.domain.OrderNumber.from(2026, 1),
                        customerId,
                        "customer@example.com",
                        "USD",
                        new com.example.omp.order.domain.ShippingAddress(
                                "1 Main", null, "Chicago", "IL", "60601", "US"),
                        UUID.randomUUID());
        for (UUID reservation : List.of(firstReservation, secondReservation)) {
            var line =
                    new com.example.omp.order.domain.OrderLine(
                            UUID.randomUUID(),
                            "SKU-" + reservation,
                            "Product",
                            com.example.omp.shared.domain.Money.usd("1"),
                            1);
            line.setReservationId(reservation);
            order.addLine(line);
        }
        when(tx.findAndApply(any(), any()))
                .thenAnswer(
                        invocation -> {
                            java.util.function.Consumer<Order> mutation = invocation.getArgument(1);
                            mutation.accept(order);
                            return order;
                        });
        when(tx.findByIdFull(id)).thenReturn(order);
        orderService.cancel(id, "alice", "Changed mind");
        orderService.cancel(id, "alice", "Retry cleanup");
        org.mockito.Mockito.verify(cleanup, org.mockito.Mockito.times(2)).retryOrder(id, 100);
        org.mockito.Mockito.verify(cleanup, org.mockito.Mockito.times(2))
                .enqueue(id, "RESERVATION", secondReservation);
        org.mockito.Mockito.verify(eventPublisher).publishCancelled(order, "alice", "Changed mind");
        org.assertj.core.api.Assertions.assertThat(order.getStatus())
                .isEqualTo(com.example.omp.order.domain.OrderStatus.CANCELLED);
        org.assertj.core.api.Assertions.assertThat(order.getStatusHistory()).hasSize(2);
    }

    @Mock private OrderRepository orderRepository;
    @Mock private OrderTransactionService tx;
    @Mock private ProductClient productClient;
    @Mock private CustomerClient customerClient;
    @Mock private InventoryClient inventoryClient;
    @Mock private PaymentClient paymentClient;
    @Mock private OrderEventPublisher eventPublisher;
    @Mock private OrderCleanupQueue cleanup;
    @Mock private CheckoutJournal journal;

    @Test
    void oversizedTransitionReasonsFailBeforeAnyTransactionOrDownstreamCall() {
        UUID id = UUID.randomUUID();
        String reason = "x".repeat(501);
        assertThatThrownBy(() -> orderService.confirm(id, "operator", reason))
                .isInstanceOf(com.example.omp.order.error.InvalidOrderDataException.class);
        assertThatThrownBy(() -> orderService.ship(id, "operator", reason))
                .isInstanceOf(com.example.omp.order.error.InvalidOrderDataException.class);
        assertThatThrownBy(() -> orderService.deliver(id, "operator", reason))
                .isInstanceOf(com.example.omp.order.error.InvalidOrderDataException.class);
        assertThatThrownBy(() -> orderService.cancel(id, "operator", reason))
                .isInstanceOf(com.example.omp.order.error.InvalidOrderDataException.class);
        org.mockito.Mockito.verifyNoInteractions(
                tx, paymentClient, inventoryClient, eventPublisher, cleanup);
    }

    @Test
    void transitionsRequireABoundedVerifiedActor() {
        UUID id = UUID.randomUUID();
        for (String actor : java.util.Arrays.asList(null, "", " ", "x".repeat(101))) {
            assertThatThrownBy(() -> orderService.place(id, actor))
                    .isInstanceOf(com.example.omp.order.error.InvalidOrderDataException.class);
            assertThatThrownBy(() -> orderService.cancel(id, actor, "reason"))
                    .isInstanceOf(com.example.omp.order.error.InvalidOrderDataException.class);
        }
        org.mockito.Mockito.verifyNoInteractions(
                tx, paymentClient, inventoryClient, eventPublisher, cleanup);
    }

    private OrderService orderService;

    private final UUID customerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orderService =
                new OrderService(
                        orderRepository,
                        tx,
                        productClient,
                        customerClient,
                        eventPublisher,
                        inventoryClient,
                        paymentClient,
                        cleanup,
                        journal,
                        org.mockito.Mockito.mock(OrderFulfillmentStore.class),
                        org.mockito.Mockito.mock(OrderFulfillmentQueue.class),
                        org.mockito.Mockito.mock(
                                com.example.omp.order.service.OrderLifecycleCoordinator.class));
    }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    @Test
    void paymentFailureQueuesKnownReservationsBeforeRetryingCleanup() {
        when(customerClient.getCustomer(customerId))
                .thenReturn(
                        new CustomerDto(
                                customerId, "a@b.com", "A", "B", "ACTIVE", List.of(), null));
        when(productClient.getProduct(productId))
                .thenReturn(
                        new ProductDto(
                                productId,
                                "SKU-1",
                                "Product",
                                null,
                                new ProductDto.MoneyDto(new BigDecimal("9.99"), "USD"),
                                "ACTIVE"));
        UUID reservationId = UUID.randomUUID();
        var reservation =
                org.mockito.Mockito.mock(
                        com.example.omp.order.clients.grpc.dto.ReservationDto.class);
        when(reservation.id()).thenReturn(reservationId);
        when(inventoryClient.reserve(
                        any(),
                        org.mockito.ArgumentMatchers.eq("SKU-1"),
                        org.mockito.ArgumentMatchers.eq("WH-1"),
                        org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(reservation);
        when(paymentClient.createIntent(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Provider unavailable"));
        var request =
                new CreateOrderRequest(
                        customerId,
                        new CreateOrderRequest.ShippingAddressRequest(
                                "1", null, "City", null, "12345", "US"),
                        List.of(new CreateOrderRequest.LineRequest(productId, 1)),
                        null);
        assertThatThrownBy(() -> orderService.create(request)).hasMessage("Provider unavailable");
        var ordered = org.mockito.Mockito.inOrder(cleanup);
        ordered.verify(cleanup)
                .enqueue(
                        any(),
                        org.mockito.ArgumentMatchers.eq("RESERVATION"),
                        org.mockito.ArgumentMatchers.eq(reservationId));
        ordered.verify(cleanup).retryOrder(any(), org.mockito.ArgumentMatchers.eq(100));
        org.mockito.Mockito.verifyNoInteractions(tx);
    }

    @Test
    void duplicateReservationIdentityCannotAcceptAnOrderAndCompensatesOnlyOnce() {
        when(customerClient.getCustomer(customerId))
                .thenReturn(
                        new CustomerDto(
                                customerId, "a@b.com", "A", "B", "ACTIVE", List.of(), null));
        UUID secondProduct = UUID.randomUUID();
        when(productClient.getProduct(any()))
                .thenAnswer(
                        invocation -> {
                            UUID id = invocation.getArgument(0);
                            return new ProductDto(
                                    id,
                                    id.equals(productId) ? "SKU-1" : "SKU-2",
                                    "Product",
                                    null,
                                    new ProductDto.MoneyDto(new BigDecimal("9.99"), "USD"),
                                    "ACTIVE");
                        });
        UUID reservationId = UUID.randomUUID();
        when(inventoryClient.reserve(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(
                        invocation ->
                                new com.example.omp.order.clients.grpc.dto.ReservationDto(
                                        reservationId,
                                        invocation.getArgument(1),
                                        invocation.getArgument(2),
                                        invocation.getArgument(3),
                                        invocation.getArgument(0),
                                        "RESERVATION_STATUS_ACTIVE",
                                        0,
                                        0));
        var request =
                new CreateOrderRequest(
                        customerId,
                        new CreateOrderRequest.ShippingAddressRequest(
                                "1", null, "City", null, "12345", "US"),
                        List.of(
                                new CreateOrderRequest.LineRequest(productId, 1),
                                new CreateOrderRequest.LineRequest(secondProduct, 1)),
                        null);

        assertThatThrownBy(() -> orderService.create(request))
                .isInstanceOf(com.example.omp.order.error.DownstreamServiceException.class)
                .hasMessageContaining("Duplicate checkout reservation identity");
        org.mockito.Mockito.verify(journal).fail(any());
        org.mockito.Mockito.verify(cleanup)
                .enqueue(
                        any(),
                        org.mockito.ArgumentMatchers.eq("RESERVATION"),
                        org.mockito.ArgumentMatchers.eq(reservationId));
        org.mockito.Mockito.verify(cleanup).retryOrder(any(), org.mockito.ArgumentMatchers.eq(100));
        org.mockito.Mockito.verifyNoInteractions(tx, paymentClient, eventPublisher);
    }

    @Test
    void duplicateSkuIsRejectedBeforeReservingOrCharging() {
        when(customerClient.getCustomer(customerId))
                .thenReturn(
                        new CustomerDto(
                                customerId, "a@b.com", "A", "B", "ACTIVE", List.of(), null));
        when(productClient.getProduct(productId))
                .thenReturn(
                        new ProductDto(
                                productId,
                                "SKU-1",
                                "Product",
                                null,
                                new ProductDto.MoneyDto(new BigDecimal("9.99"), "USD"),
                                "ACTIVE"));
        var request =
                new CreateOrderRequest(
                        customerId,
                        new CreateOrderRequest.ShippingAddressRequest(
                                "1", null, "City", null, "12345", "US"),
                        List.of(
                                new CreateOrderRequest.LineRequest(productId, 1),
                                new CreateOrderRequest.LineRequest(productId, 1)),
                        null);
        assertThatThrownBy(() -> orderService.create(request))
                .isInstanceOf(com.example.omp.order.error.InvalidOrderDataException.class);
        org.mockito.Mockito.verifyNoInteractions(inventoryClient, paymentClient, cleanup, tx);
    }

    @Test
    void createRejectsMixedCurrencyLines() {
        when(customerClient.getCustomer(customerId))
                .thenReturn(
                        new CustomerDto(
                                customerId, "a@b.com", "A", "B", "ACTIVE", List.of(), null));
        when(productClient.getProduct(productId))
                .thenReturn(
                        new ProductDto(
                                productId,
                                "SKU-1",
                                "Name",
                                null,
                                new ProductDto.MoneyDto(new BigDecimal("9.99"), "EUR"),
                                "ACTIVE"));

        var request =
                new CreateOrderRequest(
                        customerId,
                        new CreateOrderRequest.ShippingAddressRequest(
                                "1", null, "City", null, "12345", "US"),
                        List.of(new CreateOrderRequest.LineRequest(productId, 1)),
                        null);

        UUID secondId = UUID.randomUUID();
        when(productClient.getProduct(secondId))
                .thenReturn(
                        new ProductDto(
                                secondId,
                                "SKU-2",
                                "Second",
                                null,
                                new ProductDto.MoneyDto(new BigDecimal("5.00"), "USD"),
                                "ACTIVE"));
        var mixed =
                new CreateOrderRequest(
                        customerId,
                        request.shippingAddress(),
                        List.of(
                                new CreateOrderRequest.LineRequest(productId, 1),
                                new CreateOrderRequest.LineRequest(secondId, 1)),
                        null);
        assertThatThrownBy(() -> orderService.create(mixed))
                .isInstanceOf(com.example.omp.order.error.InvalidOrderDataException.class);
    }

    @Test
    void createPropagatesDownstreamProductNotFound() {
        when(customerClient.getCustomer(customerId))
                .thenReturn(
                        new CustomerDto(
                                customerId, "a@b.com", "A", "B", "ACTIVE", List.of(), null));
        when(productClient.getProduct(productId))
                .thenThrow(
                        new com.example.omp.order.error.ProductUnavailableException("not found"));

        var request =
                new CreateOrderRequest(
                        customerId,
                        new CreateOrderRequest.ShippingAddressRequest(
                                "1", null, "City", null, "12345", "US"),
                        List.of(new CreateOrderRequest.LineRequest(productId, 1)),
                        null);

        assertThatThrownBy(() -> orderService.create(request))
                .isInstanceOf(com.example.omp.order.error.ProductUnavailableException.class);
    }

    // -------------------------------------------------------------------------
    // state transitions
    // -------------------------------------------------------------------------

    @Test
    void placeRejectsAlreadyPlacedOrder() {
        UUID orderId = UUID.randomUUID();
        // The entity throws IllegalStateException; the service translates
        // to InvalidOrderStateException.
        when(tx.findAndApply(any(), any()))
                .thenAnswer(
                        inv -> {
                            var mutation = (java.util.function.Consumer<Order>) inv.getArgument(1);
                            Order placed = org.mockito.Mockito.mock(Order.class);
                            org.mockito.Mockito.doThrow(new IllegalStateException("Already placed"))
                                    .when(placed)
                                    .place(any());
                            mutation.accept(placed);
                            return placed;
                        });

        assertThatThrownBy(() -> orderService.place(orderId, "alice"))
                .isInstanceOf(com.example.omp.order.error.InvalidOrderStateException.class);
    }
}
