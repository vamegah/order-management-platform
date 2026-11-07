package com.example.omp.order.clients.grpc.interceptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.grpc.GrpcExceptionMapper;
import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.PaymentClient;
import com.example.omp.order.service.CheckoutJournal;
import com.example.omp.order.service.CheckoutRecoveryJob;
import com.example.omp.order.service.OrderCleanupQueue;
import com.example.omp.proto.inventory.*;
import com.example.omp.proto.payment.*;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/** Exercise recovery discovery through the generated inventory and payment RPC clients. */
class RecoveryDiscoveryContractTest {
    @TempDir Path directory;
    private final CheckoutJournal journal = mock(CheckoutJournal.class);
    private final OrderCleanupQueue cleanup = mock(OrderCleanupQueue.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final CheckoutJournal.Attempt attempt =
            new CheckoutJournal.Attempt(UUID.randomUUID(), UUID.randomUUID());
    private final UUID customerId = UUID.randomUUID();
    private final List<UUID> inventoryRequests = new ArrayList<>();
    private final List<UUID> paymentRequests = new ArrayList<>();
    private UnaryOperator<List<Reservation>> reservationReply = UnaryOperator.identity();
    private UnaryOperator<List<Payment>> paymentReply = UnaryOperator.identity();
    private Server server;
    private ManagedChannel channel;
    private CheckoutRecoveryJob job;

    @BeforeEach
    void start() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                new InventoryServiceGrpc.InventoryServiceImplBase() {
                                    @Override
                                    public void findReservationsByOrder(
                                            FindReservationsByOrderRequest request,
                                            StreamObserver<FindReservationsByOrderResponse>
                                                    observer) {
                                        UUID orderId = UUID.fromString(request.getOrderId());
                                        inventoryRequests.add(orderId);
                                        var reservation =
                                                Reservation.newBuilder()
                                                        .setId(
                                                                resourceId("reservation", orderId)
                                                                        .toString())
                                                        .setOrderId(request.getOrderId())
                                                        .setSku("SKU-1")
                                                        .setWarehouseId("WH-1")
                                                        .setQuantity(1)
                                                        .setStatus(
                                                                ReservationStatus
                                                                        .RESERVATION_STATUS_ACTIVE)
                                                        .build();
                                        observer.onNext(
                                                FindReservationsByOrderResponse.newBuilder()
                                                        .addAllReservations(
                                                                reservationReply.apply(
                                                                        List.of(reservation)))
                                                        .build());
                                        observer.onCompleted();
                                    }
                                })
                        .addService(
                                new PaymentServiceGrpc.PaymentServiceImplBase() {
                                    @Override
                                    public void findPaymentsByOrder(
                                            FindPaymentsByOrderRequest request,
                                            StreamObserver<FindPaymentsByOrderResponse> observer) {
                                        UUID orderId = UUID.fromString(request.getOrderId());
                                        paymentRequests.add(orderId);
                                        var payment =
                                                Payment.newBuilder()
                                                        .setId(
                                                                resourceId("payment", orderId)
                                                                        .toString())
                                                        .setOrderId(request.getOrderId())
                                                        .setCustomerId(customerId.toString())
                                                        .setStatus(
                                                                PaymentStatus
                                                                        .PAYMENT_STATUS_PENDING)
                                                        .build();
                                        observer.onNext(
                                                FindPaymentsByOrderResponse.newBuilder()
                                                        .addAllPayments(
                                                                paymentReply.apply(
                                                                        List.of(payment)))
                                                        .build());
                                        observer.onCompleted();
                                    }
                                })
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        var token = directory.resolve("token");
        Files.writeString(token, "service.payload.signature");
        when(journal.claim(10)).thenReturn(List.of(attempt));
        job =
                new CheckoutRecoveryJob(
                        journal,
                        cleanup,
                        new InventoryClient(
                                InventoryServiceGrpc.newBlockingStub(channel),
                                new GrpcExceptionMapper()),
                        new PaymentClient(
                                PaymentServiceGrpc.newBlockingStub(channel),
                                new GrpcExceptionMapper()),
                        metrics,
                        token);
    }

    @AfterEach
    void stop() throws Exception {
        assertThat(ServiceTokenScope.current()).isNull();
        if (channel != null) {
            channel.shutdownNow();
            assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        if (server != null) {
            server.shutdownNow();
            assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        metrics.close();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "reservation-foreign",
                "reservation-invalid-id",
                "reservation-invalid-owner",
                "reservation-duplicate",
                "reservation-overlimit",
                "payment-foreign",
                "payment-invalid-id",
                "payment-invalid-owner",
                "payment-duplicate",
                "payment-overlimit"
            })
    void invalidLookupCannotQueueItsPrefixAndRemainsRetryable(String fault) {
        UUID foreignId = UUID.randomUUID();
        if (fault.startsWith("reservation-")) {
            reservationReply =
                    valid -> {
                        var first = valid.getFirst();
                        var invalid = first.toBuilder().setId(foreignId.toString());
                        switch (fault) {
                            case "reservation-foreign" ->
                                    invalid.setOrderId(UUID.randomUUID().toString());
                            case "reservation-invalid-id" -> invalid.setId("invalid-remote-id");
                            case "reservation-invalid-owner" ->
                                    invalid.setOrderId("invalid-remote-order");
                            case "reservation-duplicate" -> {
                                return List.of(first, first);
                            }
                            case "reservation-overlimit" -> {
                                return IntStream.range(0, 101)
                                        .mapToObj(
                                                index ->
                                                        first.toBuilder()
                                                                .setId(UUID.randomUUID().toString())
                                                                .build())
                                        .toList();
                            }
                            default -> throw new AssertionError(fault);
                        }
                        return List.of(first, invalid.build());
                    };
        } else {
            paymentReply =
                    valid -> {
                        var first = valid.getFirst();
                        var invalid = first.toBuilder().setId(foreignId.toString());
                        switch (fault) {
                            case "payment-foreign" ->
                                    invalid.setOrderId(UUID.randomUUID().toString());
                            case "payment-invalid-id" -> invalid.setId("invalid-remote-id");
                            case "payment-invalid-owner" ->
                                    invalid.setOrderId("invalid-remote-order");
                            case "payment-duplicate" -> {
                                return List.of(first, first);
                            }
                            case "payment-overlimit" -> {
                                return IntStream.range(0, 101)
                                        .mapToObj(
                                                index ->
                                                        first.toBuilder()
                                                                .setId(UUID.randomUUID().toString())
                                                                .build())
                                        .toList();
                            }
                            default -> throw new AssertionError(fault);
                        }
                        return List.of(first, invalid.build());
                    };
        }

        job.run();

        verify(journal).finish(attempt, false);
        verify(journal, never()).finish(attempt, true);
        assertThat(metrics.get("order.recovery.failures").counter().count()).isEqualTo(1);
        if (fault.startsWith("reservation-")) {
            verify(cleanup, never()).enqueue(any(), any(), any());
            assertThat(paymentRequests).isEmpty();
        } else {
            verify(cleanup)
                    .enqueue(
                            attempt.orderId(),
                            "RESERVATION",
                            resourceId("reservation", attempt.orderId()));
            verify(cleanup, never()).enqueue(any(), eq("PAYMENT"), any());
        }
        verify(cleanup).retryDue();
        assertThat(ServiceTokenScope.current()).isNull();

        reservationReply = UnaryOperator.identity();
        paymentReply = UnaryOperator.identity();
        job.run();

        verify(journal).finish(attempt, true);
        verify(cleanup)
                .enqueue(attempt.orderId(), "PAYMENT", resourceId("payment", attempt.orderId()));
        verify(cleanup, never()).enqueue(any(), any(), eq(foreignId));
        verify(cleanup, times(2)).retryDue();
        assertThat(metrics.get("order.recovery.scans").counter().count()).isEqualTo(1);
    }

    @Test
    void badAttemptDoesNotBlockAnotherOrdersDiscovery() {
        var next = new CheckoutJournal.Attempt(UUID.randomUUID(), UUID.randomUUID());
        when(journal.claim(10)).thenReturn(List.of(attempt, next));
        reservationReply =
                valid ->
                        valid.getFirst().getOrderId().equals(attempt.orderId().toString())
                                ? List.of(
                                        valid.getFirst().toBuilder()
                                                .setOrderId(UUID.randomUUID().toString())
                                                .build())
                                : valid;

        job.run();

        assertThat(inventoryRequests).containsExactly(attempt.orderId(), next.orderId());
        assertThat(paymentRequests).containsExactly(next.orderId());
        verify(journal).finish(attempt, false);
        verify(journal).finish(next, true);
        verify(cleanup, never()).enqueue(eq(attempt.orderId()), any(), any());
        verify(cleanup)
                .enqueue(next.orderId(), "RESERVATION", resourceId("reservation", next.orderId()));
        verify(cleanup).enqueue(next.orderId(), "PAYMENT", resourceId("payment", next.orderId()));
        verify(cleanup).retryDue();
    }

    @Test
    void maximumBoundedResultsQueueEveryDistinctOwnedResource() {
        reservationReply =
                valid ->
                        IntStream.range(0, 100)
                                .mapToObj(
                                        index ->
                                                valid.getFirst().toBuilder()
                                                        .setId(UUID.randomUUID().toString())
                                                        .build())
                                .toList();
        paymentReply =
                valid ->
                        IntStream.range(0, 100)
                                .mapToObj(
                                        index ->
                                                valid.getFirst().toBuilder()
                                                        .setId(UUID.randomUUID().toString())
                                                        .build())
                                .toList();

        job.run();

        var reservations = ArgumentCaptor.forClass(UUID.class);
        var payments = ArgumentCaptor.forClass(UUID.class);
        verify(cleanup, times(100))
                .enqueue(eq(attempt.orderId()), eq("RESERVATION"), reservations.capture());
        verify(cleanup, times(100))
                .enqueue(eq(attempt.orderId()), eq("PAYMENT"), payments.capture());
        assertThat(reservations.getAllValues()).doesNotHaveDuplicates();
        assertThat(payments.getAllValues()).doesNotHaveDuplicates();
        verify(journal).finish(attempt, true);
        verify(cleanup).retryDue();
    }

    @Test
    void emptyLookupsCompleteAScanAndStillRetryExistingCleanup() {
        reservationReply = valid -> List.of();
        paymentReply = valid -> List.of();

        job.run();

        verify(journal).finish(attempt, true);
        verify(cleanup, never()).enqueue(any(), any(), any());
        verify(cleanup).retryDue();
        assertThat(paymentRequests).containsExactly(attempt.orderId());
    }

    private static UUID resourceId(String type, UUID orderId) {
        return UUID.nameUUIDFromBytes((type + "/" + orderId).getBytes(StandardCharsets.UTF_8));
    }
}
