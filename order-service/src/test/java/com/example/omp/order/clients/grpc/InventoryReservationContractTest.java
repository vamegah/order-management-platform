package com.example.omp.order.clients.grpc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.CustomerClient;
import com.example.omp.order.clients.ProductClient;
import com.example.omp.order.clients.dto.CustomerDto;
import com.example.omp.order.clients.dto.ProductDto;
import com.example.omp.order.dto.CreateOrderRequest;
import com.example.omp.order.error.DownstreamServiceException;
import com.example.omp.order.events.OrderEventPublisher;
import com.example.omp.order.repository.OrderRepository;
import com.example.omp.order.service.*;
import com.example.omp.proto.inventory.*;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Malformed inventory replies must not become accepted checkout references. */
class InventoryReservationContractTest {
    private final UUID order = UUID.randomUUID(), reservation = UUID.randomUUID();
    private UnaryOperator<Reservation> reply = UnaryOperator.identity();
    private Server server;
    private ManagedChannel channel;
    private InventoryClient client;
    private ReserveStockRequest received;

    @BeforeEach
    void start() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                new InventoryServiceGrpc.InventoryServiceImplBase() {
                                    @Override
                                    public void reserveStock(
                                            ReserveStockRequest request,
                                            StreamObserver<Reservation> observer) {
                                        received = request;
                                        var valid =
                                                Reservation.newBuilder()
                                                        .setId(reservation.toString())
                                                        .setOrderId(request.getOrderId())
                                                        .setSku(request.getSku())
                                                        .setWarehouseId(request.getWarehouseId())
                                                        .setQuantity(request.getQuantity())
                                                        .setStatus(
                                                                ReservationStatus
                                                                        .RESERVATION_STATUS_ACTIVE)
                                                        .build();
                                        observer.onNext(reply.apply(valid));
                                        observer.onCompleted();
                                    }
                                })
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client =
                new InventoryClient(
                        InventoryServiceGrpc.newBlockingStub(channel), new GrpcExceptionMapper());
    }

    @AfterEach
    void stop() throws Exception {
        if (channel != null) {
            channel.shutdownNow();
            assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        if (server != null) {
            server.shutdownNow();
            assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void matchingActiveHoldReturnsItsDurableIdentity() {
        var result = client.reserve(order, "SKU-1", "WH-2", 3);

        assertThat(result.id()).isEqualTo(reservation);
        assertThat(result.orderId()).isEqualTo(order);
        assertThat(result.sku()).isEqualTo("SKU-1");
        assertThat(result.warehouseId()).isEqualTo("WH-2");
        assertThat(result.quantity()).isEqualTo(3);
        assertThat(result.status()).isEqualTo("RESERVATION_STATUS_ACTIVE");
    }

    @Test
    void absentWarehouseUsesAndValidatesTheExistingDefault() {
        assertThat(client.reserve(order, "SKU-1", null, 1).warehouseId()).isEqualTo("WH-1");
        assertThat(received.getWarehouseId()).isEqualTo("WH-1");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "order",
                "sku",
                "warehouse",
                "quantity",
                "committed",
                "released",
                "expired",
                "unspecified",
                "unrecognized",
                "malformed-id",
                "empty-id",
                "malformed-order"
            })
    void mismatchedOrMalformedReplyCannotBecomeACheckoutReference(String field) {
        reply =
                valid -> {
                    var invalid = valid.toBuilder();
                    switch (field) {
                        case "order" -> invalid.setOrderId(UUID.randomUUID().toString());
                        case "sku" -> invalid.setSku("FOREIGN-SKU");
                        case "warehouse" -> invalid.setWarehouseId("FOREIGN-WAREHOUSE");
                        case "quantity" -> invalid.setQuantity(valid.getQuantity() + 1);
                        case "committed" ->
                                invalid.setStatus(ReservationStatus.RESERVATION_STATUS_COMMITTED);
                        case "released" ->
                                invalid.setStatus(ReservationStatus.RESERVATION_STATUS_RELEASED);
                        case "expired" ->
                                invalid.setStatus(ReservationStatus.RESERVATION_STATUS_EXPIRED);
                        case "unspecified" ->
                                invalid.setStatus(ReservationStatus.RESERVATION_STATUS_UNSPECIFIED);
                        case "unrecognized" -> invalid.setStatusValue(999);
                        case "malformed-id" -> invalid.setId("invalid-remote-identity");
                        case "empty-id" -> invalid.clearId();
                        case "malformed-order" -> invalid.setOrderId("invalid-remote-order");
                        default -> throw new AssertionError(field);
                    }
                    return invalid.build();
                };

        assertThatThrownBy(() -> client.reserve(order, "SKU-1", "WH-1", 2))
                .isInstanceOfSatisfying(
                        DownstreamServiceException.class,
                        failure -> {
                            assertThat(failure.getService()).isEqualTo("inventory-service");
                            assertThat(failure.getStatus()).isEqualTo(502);
                            assertThat(failure.getMessage()).doesNotContain("invalid-remote-");
                        });
    }

    @Test
    void secondReplyMismatchPreventsAcceptanceAndCompensatesOnlyTheVerifiedFirstHold() {
        UUID firstProduct = UUID.randomUUID(),
                secondProduct = UUID.randomUUID(),
                customer = UUID.randomUUID(),
                foreignReservation = UUID.randomUUID();
        reply =
                valid ->
                        valid.getSku().equals("SKU-2")
                                ? valid.toBuilder()
                                        .setId(foreignReservation.toString())
                                        .setOrderId(UUID.randomUUID().toString())
                                        .build()
                                : valid;
        var customers = mock(CustomerClient.class);
        var products = mock(ProductClient.class);
        var payments = mock(PaymentClient.class);
        var transactions = mock(OrderTransactionService.class);
        var journal = mock(CheckoutJournal.class);
        var cleanup = mock(OrderCleanupQueue.class);
        var events = mock(OrderEventPublisher.class);
        when(customers.getCustomer(customer))
                .thenReturn(
                        new CustomerDto(customer, "a@b.com", "A", "B", "ACTIVE", List.of(), null));
        when(products.getProduct(any()))
                .thenAnswer(
                        invocation -> {
                            UUID id = invocation.getArgument(0);
                            return new ProductDto(
                                    id,
                                    id.equals(firstProduct) ? "SKU-1" : "SKU-2",
                                    "Product",
                                    null,
                                    new ProductDto.MoneyDto(new BigDecimal("10"), "USD"),
                                    "ACTIVE");
                        });
        var service =
                new OrderService(
                        mock(OrderRepository.class),
                        transactions,
                        products,
                        customers,
                        events,
                        client,
                        payments,
                        cleanup,
                        journal,
                        mock(OrderFulfillmentStore.class),
                        mock(OrderFulfillmentQueue.class),
                        org.mockito.Mockito.mock(
                                com.example.omp.order.service.OrderLifecycleCoordinator.class));
        var request =
                new CreateOrderRequest(
                        customer,
                        new CreateOrderRequest.ShippingAddressRequest(
                                "1 Main", null, "City", null, "12345", "US"),
                        List.of(
                                new CreateOrderRequest.LineRequest(firstProduct, 1),
                                new CreateOrderRequest.LineRequest(secondProduct, 1)),
                        null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DownstreamServiceException.class);
        verify(journal).fail(any());
        verify(cleanup).enqueue(any(), eq("RESERVATION"), eq(reservation));
        verify(cleanup, never()).enqueue(any(), any(), eq(foreignReservation));
        verify(cleanup).retryOrder(any(), eq(100));
        verifyNoInteractions(transactions, payments, events);
    }
}
