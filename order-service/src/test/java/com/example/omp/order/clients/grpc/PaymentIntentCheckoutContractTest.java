package com.example.omp.order.clients.grpc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.CustomerClient;
import com.example.omp.order.clients.ProductClient;
import com.example.omp.order.clients.dto.CustomerDto;
import com.example.omp.order.clients.dto.ProductDto;
import com.example.omp.order.clients.grpc.dto.ReservationDto;
import com.example.omp.order.dto.CreateOrderRequest;
import com.example.omp.order.error.DownstreamServiceException;
import com.example.omp.order.events.OrderEventPublisher;
import com.example.omp.order.repository.OrderRepository;
import com.example.omp.order.service.*;
import com.example.omp.proto.payment.*;
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

/** Payment replies must describe this checkout before becoming durable order references. */
class PaymentIntentCheckoutContractTest {
    private final UUID orderId = UUID.randomUUID(),
            customerId = UUID.randomUUID(),
            paymentId = UUID.randomUUID(),
            reservationId = UUID.randomUUID(),
            productId = UUID.randomUUID();
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final OrderTransactionService transactions = mock(OrderTransactionService.class);
    private final CheckoutJournal journal = mock(CheckoutJournal.class);
    private final OrderCleanupQueue cleanup = mock(OrderCleanupQueue.class);
    private final OrderEventPublisher events = mock(OrderEventPublisher.class);
    private UnaryOperator<Payment> reply = UnaryOperator.identity();
    private Server server;
    private ManagedChannel channel;
    private PaymentClient client;

    @BeforeEach
    void start() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                new PaymentServiceGrpc.PaymentServiceImplBase() {
                                    @Override
                                    public void createPaymentIntent(
                                            CreatePaymentIntentRequest request,
                                            StreamObserver<Payment> observer) {
                                        var valid =
                                                Payment.newBuilder()
                                                        .setId(paymentId.toString())
                                                        .setOrderId(request.getOrderId())
                                                        .setCustomerId(request.getCustomerId())
                                                        .setAmount(request.getAmount())
                                                        .setStatus(
                                                                PaymentStatus
                                                                        .PAYMENT_STATUS_PENDING)
                                                        .build();
                                        observer.onNext(reply.apply(valid));
                                        observer.onCompleted();
                                    }
                                })
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client =
                new PaymentClient(
                        PaymentServiceGrpc.newBlockingStub(channel), new GrpcExceptionMapper());
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

    @ParameterizedTest
    @ValueSource(strings = {"10", "10.0", "10.00", "10.0000"})
    void matchingIntentAcceptsEquivalentDecimalRepresentations(String amount) {
        reply =
                valid ->
                        valid.toBuilder()
                                .setAmount(valid.getAmount().toBuilder().setAmount(amount))
                                .build();

        var payment = client.createIntent(orderId, customerId, "10.00", "USD", "key");

        assertThat(payment.id()).isEqualTo(paymentId);
        assertThat(payment.orderId()).isEqualTo(orderId);
        assertThat(payment.customerId()).isEqualTo(customerId);
        assertThat(new BigDecimal(payment.amount())).isEqualByComparingTo("10.00");
        assertThat(payment.status()).isEqualTo("PAYMENT_STATUS_PENDING");
    }

    @Test
    void idempotentReplayCanReturnAMatchingAlreadyConfirmedPayment() {
        reply =
                valid ->
                        valid.toBuilder()
                                .setStatus(PaymentStatus.PAYMENT_STATUS_CONFIRMED)
                                .setProviderReference("provider-confirmed")
                                .build();

        var payment = client.createIntent(orderId, customerId, "10.00", "USD", "key");

        assertThat(payment.id()).isEqualTo(paymentId);
        assertThat(payment.status()).isEqualTo("PAYMENT_STATUS_CONFIRMED");
        assertThat(payment.providerReference()).isEqualTo("provider-confirmed");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "foreign-order",
                "foreign-customer",
                "currency",
                "amount",
                "missing-amount",
                "empty-amount",
                "negative-amount",
                "zero-amount",
                "scientific-amount",
                "precision-amount",
                "large-amount",
                "malformed-id",
                "empty-id",
                "malformed-order",
                "malformed-customer",
                "cancelled",
                "failed",
                "partially-refunded",
                "refunded",
                "unspecified",
                "unrecognized",
                "confirmed-missing-reference"
            })
    void invalidIntentCannotBecomeACheckoutReference(String field) {
        reply =
                valid -> {
                    var invalid = valid.toBuilder();
                    var money = valid.getAmount().toBuilder();
                    switch (field) {
                        case "foreign-order" -> invalid.setOrderId(UUID.randomUUID().toString());
                        case "foreign-customer" ->
                                invalid.setCustomerId(UUID.randomUUID().toString());
                        case "currency" -> invalid.setAmount(money.setCurrency("EUR"));
                        case "amount" -> invalid.setAmount(money.setAmount("11.00"));
                        case "missing-amount" -> invalid.clearAmount();
                        case "empty-amount" -> invalid.setAmount(money.clearAmount());
                        case "negative-amount" -> invalid.setAmount(money.setAmount("-10.00"));
                        case "zero-amount" -> invalid.setAmount(money.setAmount("0"));
                        case "scientific-amount" -> invalid.setAmount(money.setAmount("1e1"));
                        case "precision-amount" -> invalid.setAmount(money.setAmount("10.00000"));
                        case "large-amount" ->
                                invalid.setAmount(money.setAmount("1000000000000000"));
                        case "malformed-id" -> invalid.setId("invalid-remote-payment");
                        case "empty-id" -> invalid.clearId();
                        case "malformed-order" -> invalid.setOrderId("invalid-remote-order");
                        case "malformed-customer" ->
                                invalid.setCustomerId("invalid-remote-customer");
                        case "cancelled" ->
                                invalid.setStatus(PaymentStatus.PAYMENT_STATUS_CANCELLED);
                        case "failed" -> invalid.setStatus(PaymentStatus.PAYMENT_STATUS_FAILED);
                        case "partially-refunded" ->
                                invalid.setStatus(PaymentStatus.PAYMENT_STATUS_PARTIALLY_REFUNDED);
                        case "refunded" -> invalid.setStatus(PaymentStatus.PAYMENT_STATUS_REFUNDED);
                        case "unspecified" ->
                                invalid.setStatus(PaymentStatus.PAYMENT_STATUS_UNSPECIFIED);
                        case "unrecognized" -> invalid.setStatusValue(999);
                        case "confirmed-missing-reference" ->
                                invalid.setStatus(PaymentStatus.PAYMENT_STATUS_CONFIRMED)
                                        .setProviderReference("  ");
                        default -> throw new AssertionError(field);
                    }
                    return invalid.build();
                };

        assertThatThrownBy(() -> client.createIntent(orderId, customerId, "10.00", "USD", "key"))
                .isInstanceOfSatisfying(
                        DownstreamServiceException.class,
                        failure -> {
                            assertThat(failure.getService()).isEqualTo("payment-service");
                            assertThat(failure.getStatus()).isEqualTo(502);
                            assertThat(failure.getMessage()).doesNotContain("invalid-remote-");
                        });
    }

    @Test
    void foreignPaymentStopsAcceptanceAndCompensatesOnlyVerifiedInventory() {
        reply = valid -> valid.toBuilder().setOrderId(UUID.randomUUID().toString()).build();
        var service = checkoutService();

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOf(DownstreamServiceException.class);

        verify(journal).fail(any());
        verify(cleanup).enqueue(any(), eq("RESERVATION"), eq(reservationId));
        verify(cleanup, never()).enqueue(any(), eq("PAYMENT"), any());
        verify(cleanup).retryOrder(any(), eq(100));
        verifyNoInteractions(transactions, events);
    }

    @Test
    void verifiedPaymentStillReceivesCompensationWhenLocalAcceptanceFails() {
        var service = checkoutService();
        when(transactions.create(any(), any(), any(), anyList(), eq(paymentId), anyList()))
                .thenThrow(new IllegalStateException("Database unavailable"));

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Database unavailable");

        verify(journal).fail(any());
        verify(cleanup).enqueue(any(), eq("RESERVATION"), eq(reservationId));
        verify(cleanup).enqueue(any(), eq("PAYMENT"), eq(paymentId));
        verify(cleanup).retryOrder(any(), eq(100));
        verifyNoInteractions(events);
    }

    private OrderService checkoutService() {
        var customers = mock(CustomerClient.class);
        var products = mock(ProductClient.class);
        when(customers.getCustomer(customerId))
                .thenReturn(
                        new CustomerDto(
                                customerId, "a@b.com", "A", "B", "ACTIVE", List.of(), null));
        when(products.getProduct(productId))
                .thenReturn(
                        new ProductDto(
                                productId,
                                "SKU-1",
                                "Product",
                                null,
                                new ProductDto.MoneyDto(new BigDecimal("10.00"), "USD"),
                                "ACTIVE"));
        when(inventory.reserve(any(), eq("SKU-1"), eq("WH-1"), eq(1)))
                .thenAnswer(
                        invocation ->
                                new ReservationDto(
                                        reservationId,
                                        "SKU-1",
                                        "WH-1",
                                        1,
                                        invocation.getArgument(0),
                                        "RESERVATION_STATUS_ACTIVE",
                                        0,
                                        0));
        return new OrderService(
                mock(OrderRepository.class),
                transactions,
                products,
                customers,
                events,
                inventory,
                client,
                cleanup,
                journal,
                mock(OrderFulfillmentStore.class),
                mock(OrderFulfillmentQueue.class),
                org.mockito.Mockito.mock(
                        com.example.omp.order.service.OrderLifecycleCoordinator.class));
    }

    private CreateOrderRequest request() {
        return new CreateOrderRequest(
                customerId,
                new CreateOrderRequest.ShippingAddressRequest(
                        "1 Main", null, "City", null, "12345", "US"),
                List.of(new CreateOrderRequest.LineRequest(productId, 1)),
                null);
    }
}
