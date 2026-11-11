package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.grpc.*;
import com.example.omp.order.clients.grpc.dto.PaymentIntentDto;
import com.example.omp.order.clients.grpc.interceptor.AuthForwardingInterceptor;
import com.example.omp.order.clients.grpc.interceptor.ServiceTokenScope;
import com.example.omp.order.domain.*;
import com.example.omp.proto.inventory.*;
import com.example.omp.proto.payment.FulfillmentAction;
import com.example.omp.shared.domain.Money;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real inventory RPCs; mocked persistence boundary, complemented by OrderPersistenceIT. */
class OrderFulfillmentQueueTest {
    private Server server;
    private ManagedChannel channel;
    private OrderFulfillmentStore store;
    private OrderTransactionService orders;
    private PaymentClient payments;
    private OrderFulfillmentQueue queue;
    private SimpleMeterRegistry metrics;
    private com.example.omp.order.domain.Order order;
    private OrderFulfillmentStore.Task task;
    private PaymentIntentDto payment;
    private final Map<UUID, Reservation> reservations = new HashMap<>();
    private final List<String> methods = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();
    private final List<Long> deadlines = new ArrayList<>();
    private boolean loseCommitReply;
    private boolean wrongCommitReply;

    @BeforeEach
    void setup() throws Exception {
        UUID id = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        order =
                new com.example.omp.order.domain.Order(
                        id,
                        OrderNumber.from(2026, 1),
                        UUID.randomUUID(),
                        "customer@example.com",
                        "USD",
                        new ShippingAddress("1 Main", null, "Chicago", "IL", "60601", "US"),
                        UUID.randomUUID());
        var line = new OrderLine(UUID.randomUUID(), "SKU-1", "Product", Money.usd("10"), 1);
        line.bindReservation(reservationId, "WH-1");
        order.addLine(line);
        order.place("customer");
        order.confirm("operator", "Paid");
        order.requestShipment("operator", "Requested");
        task =
                new OrderFulfillmentStore.Task(
                        UUID.randomUUID(), id, reservationId, "SKU-1", 1, UUID.randomUUID());
        reservations.put(
                reservationId,
                Reservation.newBuilder()
                        .setId(reservationId.toString())
                        .setOrderId(id.toString())
                        .setSku("SKU-1")
                        .setWarehouseId("WH-1")
                        .setQuantity(1)
                        .setStatus(ReservationStatus.RESERVATION_STATUS_ACTIVE)
                        .build());
        payment =
                new PaymentIntentDto(
                        order.getPaymentId(),
                        id,
                        order.getCustomerId(),
                        "10.0000",
                        "USD",
                        "PAYMENT_STATUS_CONFIRMED",
                        "pi-confirmed",
                        0,
                        0,
                        "SHIPPED");
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .intercept(
                                new ServerInterceptor() {
                                    @Override
                                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                                            ServerCall<ReqT, RespT> call,
                                            Metadata headers,
                                            ServerCallHandler<ReqT, RespT> next) {
                                        assertThat(
                                                        TransactionSynchronizationManager
                                                                .isActualTransactionActive())
                                                .isFalse();
                                        methods.add(call.getMethodDescriptor().getBareMethodName());
                                        tokens.add(
                                                headers.get(
                                                        Metadata.Key.of(
                                                                "Authorization",
                                                                Metadata.ASCII_STRING_MARSHALLER)));
                                        deadlines.add(
                                                Context.current()
                                                        .getDeadline()
                                                        .timeRemaining(TimeUnit.MILLISECONDS));
                                        return next.startCall(call, headers);
                                    }
                                })
                        .addService(
                                new InventoryServiceGrpc.InventoryServiceImplBase() {
                                    @Override
                                    public void getReservation(
                                            GetReservationRequest request,
                                            StreamObserver<Reservation> observer) {
                                        observer.onNext(
                                                reservations.get(
                                                        UUID.fromString(
                                                                request.getReservationId())));
                                        observer.onCompleted();
                                    }

                                    @Override
                                    public void commitReservation(
                                            CommitReservationRequest request,
                                            StreamObserver<Reservation> observer) {
                                        UUID key = UUID.fromString(request.getReservationId());
                                        var committed =
                                                reservations.get(key).toBuilder()
                                                        .setStatus(
                                                                ReservationStatus
                                                                        .RESERVATION_STATUS_COMMITTED)
                                                        .build();
                                        reservations.put(key, committed);
                                        if (loseCommitReply) {
                                            loseCommitReply = false;
                                            observer.onError(
                                                    Status.UNAVAILABLE
                                                            .withDescription(
                                                                    "private broker details")
                                                            .asRuntimeException());
                                        } else {
                                            observer.onNext(
                                                    wrongCommitReply
                                                            ? committed.toBuilder()
                                                                    .setQuantity(99)
                                                                    .build()
                                                            : committed);
                                            observer.onCompleted();
                                        }
                                    }
                                })
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        var inventory =
                new InventoryClient(
                        InventoryServiceGrpc.newBlockingStub(channel)
                                .withInterceptors(new AuthForwardingInterceptor()),
                        new GrpcExceptionMapper());
        store = mock(OrderFulfillmentStore.class);
        orders = mock(OrderTransactionService.class);
        payments = mock(PaymentClient.class);
        metrics = new SimpleMeterRegistry();
        queue = new OrderFulfillmentQueue(store, inventory, payments, orders, metrics);
        when(store.startAttempt(id)).thenReturn(true);
        when(store.claimTask(id)).thenReturn(Optional.of(task), Optional.empty());
        when(store.completeTask(any())).thenReturn(true);
        when(store.readyForCompletion(id)).thenReturn(true);
        when(orders.findByIdFull(id)).thenReturn(order);
        when(payments.get(order.getPaymentId())).thenReturn(payment);
        when(payments.decide(order.getPaymentId(), id, FulfillmentAction.FULFILLMENT_ACTION_BEGIN))
                .thenReturn(
                        new PaymentIntentDto(
                                payment.id(),
                                payment.orderId(),
                                payment.customerId(),
                                payment.amount(),
                                payment.currency(),
                                payment.status(),
                                payment.providerReference(),
                                0,
                                0,
                                "FULFILLING"));
        when(payments.decide(order.getPaymentId(), id, FulfillmentAction.FULFILLMENT_ACTION_SHIP))
                .thenReturn(payment);
        when(store.finish(id, payment))
                .thenAnswer(
                        invocation -> {
                            order.ship("operator", "Requested");
                            return true;
                        });
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                Jwt.withTokenValue("operator-jwt")
                                        .header("alg", "RS256")
                                        .subject("operator")
                                        .build()));
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsAndCommitsTheCorrectHoldBeforePaymentCheckAndFinalization() {
        assertThat(queue.retryOrder(order.getId(), 100)).isEqualTo(1);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(methods).containsExactly("GetReservation", "CommitReservation");
        assertThat(tokens).containsOnly("Bearer operator-jwt");
        assertThat(deadlines).allSatisfy(value -> assertThat(value).isBetween(1L, 5000L));
        var ordered = inOrder(store, payments);
        ordered.verify(store).completeTask(task);
        ordered.verify(payments)
                .decide(
                        order.getPaymentId(),
                        order.getId(),
                        FulfillmentAction.FULFILLMENT_ACTION_SHIP);
        ordered.verify(store).finish(order.getId(), payment);
        assertThat(metrics.get("order.fulfillment.completed").counter().count()).isEqualTo(1);
    }

    @Test
    void shipmentUsesProjectedIdentityInsteadOfRelayingTheRequestToken(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var tokenFile = directory.resolve("access.jwt");
        java.nio.file.Files.writeString(tokenFile, "header.payload.signature");
        org.springframework.test.util.ReflectionTestUtils.setField(
                queue, "tokenFile", tokenFile.toString());

        assertThat(queue.retryOrder(order.getId(), 100)).isEqualTo(1);

        assertThat(tokens).containsOnly("Bearer header.payload.signature");
        assertThat(ServiceTokenScope.isActive()).isFalse();
    }

    @Test
    void lostCommitReplyIsRecoveredByReadingCommittedHoldWithoutAnotherMutation() {
        loseCommitReply = true;
        var reclaimed =
                new OrderFulfillmentStore.Task(
                        task.id(),
                        task.orderId(),
                        task.reservationId(),
                        task.sku(),
                        task.quantity(),
                        UUID.randomUUID());
        when(store.claimTask(order.getId()))
                .thenReturn(
                        Optional.of(task),
                        Optional.empty(),
                        Optional.of(reclaimed),
                        Optional.empty());
        when(store.readyForCompletion(order.getId())).thenReturn(false, true);
        assertThat(queue.retryOrder(order.getId(), 100)).isZero();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLING);
        verify(store).failTask(eq(task), any(RuntimeException.class));
        assertThat(queue.retryOrder(order.getId(), 100)).isEqualTo(1);
        assertThat(methods)
                .containsExactly("GetReservation", "CommitReservation", "GetReservation");
        verify(store).completeTask(reclaimed);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "RESERVATION_STATUS_EXPIRED",
                "RESERVATION_STATUS_RELEASED",
                "RESERVATION_STATUS_UNSPECIFIED"
            })
    void unusableHoldNeverCommitsOrShips(String state) {
        reservations.compute(
                task.reservationId(),
                (key, value) ->
                        value.toBuilder().setStatus(ReservationStatus.valueOf(state)).build());
        when(store.readyForCompletion(order.getId())).thenReturn(false);
        assertThat(queue.retryOrder(order.getId(), 100)).isZero();
        assertThat(methods).containsExactly("GetReservation");
        verify(store, never()).completeTask(any());
        verify(store, never()).finish(any(), any());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "order", "sku", "quantity"})
    void wrongReservationIdentityIsRejectedBeforeMutation(String field) {
        var value = reservations.get(task.reservationId()).toBuilder();
        switch (field) {
            case "id" -> value.setId(UUID.randomUUID().toString());
            case "order" -> value.setOrderId(UUID.randomUUID().toString());
            case "sku" -> value.setSku("OTHER-SKU");
            case "quantity" -> value.setQuantity(2);
        }
        reservations.put(task.reservationId(), value.build());
        when(store.readyForCompletion(order.getId())).thenReturn(false);
        queue.retryOrder(order.getId(), 100);
        assertThat(methods).containsExactly("GetReservation");
        verify(store).failTask(eq(task), any(IllegalStateException.class));
        verify(store, never()).completeTask(any());
    }

    @Test
    void invalidCommitAcknowledgementIsNotPersistedAsCompleted() {
        wrongCommitReply = true;
        when(store.readyForCompletion(order.getId())).thenReturn(false);
        queue.retryOrder(order.getId(), 100);
        verify(store, never()).completeTask(any());
        verify(store).failTask(eq(task), any(IllegalStateException.class));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLING);
    }

    @Test
    void expiredHoldRequestsCompensationBeforeAnyAdditionalCommit() {
        reservations.compute(
                task.reservationId(),
                (key, value) ->
                        value.toBuilder()
                                .setStatus(ReservationStatus.RESERVATION_STATUS_EXPIRED)
                                .build());
        UUID secondId = UUID.randomUUID();
        var second =
                new OrderFulfillmentStore.Task(
                        UUID.randomUUID(), order.getId(), secondId, "SKU-2", 2, UUID.randomUUID());
        reservations.put(
                secondId,
                Reservation.newBuilder()
                        .setId(secondId.toString())
                        .setOrderId(order.getId().toString())
                        .setSku("SKU-2")
                        .setQuantity(2)
                        .setWarehouseId("WH-1")
                        .setStatus(ReservationStatus.RESERVATION_STATUS_ACTIVE)
                        .build());
        when(store.claimTask(order.getId()))
                .thenReturn(Optional.of(task), Optional.of(second), Optional.empty());
        when(store.readyForCompletion(order.getId())).thenReturn(false);
        assertThat(queue.retryOrder(order.getId(), 100)).isZero();
        verify(store).failTask(eq(task), any());
        verify(store, never()).completeTask(second);
        verify(store).requestAbort(order.getId(), "Required shipment inventory is unavailable");
        verify(store, never()).finish(any(), any());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLING);
    }

    @Test
    void aTransientLostCommitReplyDoesNotPreventProcessingAnotherOwnedHold() {
        loseCommitReply = true;
        UUID secondId = UUID.randomUUID();
        var second =
                new OrderFulfillmentStore.Task(
                        UUID.randomUUID(), order.getId(), secondId, "SKU-2", 2, UUID.randomUUID());
        reservations.put(
                secondId,
                Reservation.newBuilder()
                        .setId(secondId.toString())
                        .setOrderId(order.getId().toString())
                        .setSku("SKU-2")
                        .setQuantity(2)
                        .setWarehouseId("WH-1")
                        .setStatus(ReservationStatus.RESERVATION_STATUS_ACTIVE)
                        .build());
        when(store.claimTask(order.getId()))
                .thenReturn(Optional.of(task), Optional.of(second), Optional.empty());
        when(store.readyForCompletion(order.getId())).thenReturn(false);
        assertThat(queue.retryOrder(order.getId(), 100)).isEqualTo(1);
        verify(store).failTask(eq(task), any());
        verify(store).completeTask(second);
        verify(store, never()).finish(any(), any());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLING);
    }

    @Test
    void paymentRefundedDuringCommitPreventsShipmentCompletion() {
        when(payments.decide(
                        order.getPaymentId(),
                        order.getId(),
                        FulfillmentAction.FULFILLMENT_ACTION_SHIP))
                .thenReturn(
                        new PaymentIntentDto(
                                payment.id(),
                                payment.orderId(),
                                payment.customerId(),
                                payment.amount(),
                                payment.currency(),
                                "PAYMENT_STATUS_REFUNDED",
                                payment.providerReference(),
                                0,
                                0));
        assertThat(queue.retryOrder(order.getId(), 100)).isEqualTo(1);
        verify(store, never()).finish(any(), any());
        verify(store)
                .recordFailure(
                        eq(order.getId()),
                        any(com.example.omp.order.error.InvalidOrderStateException.class));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLING);
        assertThat(metrics.get("order.fulfillment.failures").counter().count()).isEqualTo(1);
    }

    @Test
    void inactiveRequestAndInvalidLimitCannotMutateInventory() {
        when(store.startAttempt(order.getId())).thenReturn(false);
        assertThat(queue.retryOrder(order.getId(), 1)).isZero();
        assertThatThrownBy(() -> queue.retryOrder(order.getId(), 101))
                .isInstanceOf(IllegalArgumentException.class);
        verify(store, never()).claimTask(any());
        assertThat(methods).isEmpty();
    }

    @Test
    void completionCanResumeAfterAllTaskAcknowledgementsWerePersistedBeforeRestart() {
        when(store.claimTask(order.getId())).thenReturn(Optional.empty());
        assertThat(queue.retryOrder(order.getId(), 100)).isZero();
        assertThat(methods).isEmpty();
        verify(store).finish(order.getId(), payment);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void refundedPaymentBeforeRetryCannotConsumeAnyMoreStock() {
        when(payments.decide(
                        order.getPaymentId(),
                        order.getId(),
                        FulfillmentAction.FULFILLMENT_ACTION_BEGIN))
                .thenReturn(
                        new PaymentIntentDto(
                                payment.id(),
                                payment.orderId(),
                                payment.customerId(),
                                payment.amount(),
                                payment.currency(),
                                "PAYMENT_STATUS_REFUNDED",
                                payment.providerReference(),
                                0,
                                0));
        assertThat(queue.retryOrder(order.getId(), 100)).isZero();
        verify(store, never()).claimTask(any());
        verify(store)
                .recordFailure(
                        eq(order.getId()),
                        any(com.example.omp.order.error.InvalidOrderStateException.class));
        assertThat(methods).isEmpty();
    }

    @Test
    void ambientTransactionIsRejectedBeforeClaimOrRpc() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> queue.retryOrder(order.getId(), 100))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(store, never()).startAttempt(any());
        assertThat(methods).isEmpty();
    }

    @Test
    void interruptedWorkerPreservesInterruptAndDoesNotClaimWork() {
        Thread.currentThread().interrupt();
        try {
            assertThat(queue.retryOrder(order.getId(), 100)).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(store, never()).startAttempt(any());
        assertThat(methods).isEmpty();
    }
}
