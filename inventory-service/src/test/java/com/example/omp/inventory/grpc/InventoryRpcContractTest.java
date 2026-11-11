package com.example.omp.inventory.grpc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.inventory.domain.StockLevel;
import com.example.omp.inventory.events.InventoryEventPublisher;
import com.example.omp.inventory.grpc.advice.GrpcExceptionAdvice;
import com.example.omp.inventory.grpc.handler.*;
import com.example.omp.inventory.repository.*;
import com.example.omp.proto.inventory.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.internal.testing.StreamRecorder;
import java.time.Instant;
import java.util.*;
import net.devh.boot.grpc.server.advice.*;
import net.devh.boot.grpc.server.error.GrpcExceptionInterceptor;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/** Generated clients exercise production RPC delegation and exception translation. */
class InventoryRpcContractTest {
    private final StockLevelRepository stocks = mock(StockLevelRepository.class);
    private final ReservationRepository holds = mock(ReservationRepository.class);
    private final InventoryEventPublisher events = mock(InventoryEventPublisher.class);
    private final Map<UUID, com.example.omp.inventory.domain.Reservation> saved = new HashMap<>();
    private final StockLevel stock = new StockLevel("SKU", "WH", 10);
    private AnnotationConfigApplicationContext context;
    private Server server;
    private ManagedChannel channel;
    private InventoryServiceGrpc.InventoryServiceBlockingStub client;
    private StockUpdateStreamHandler stream;

    @BeforeEach
    void start() throws Exception {
        when(holds.tryLockOrder(any())).thenReturn(true);
        ReflectionTestUtils.setField(stock, "lastUpdated", Instant.parse("2026-01-01T00:00:00Z"));
        when(stocks.findBySkuAndWarehouseId("SKU", "WH")).thenReturn(Optional.of(stock));
        when(stocks.findBySku("SKU")).thenReturn(List.of(stock));
        when(holds.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
        when(holds.findByOrderIdAndSku(any(), anyString()))
                .thenAnswer(
                        call ->
                                saved.values().stream()
                                        .filter(
                                                r ->
                                                        r.getOrderId().equals(call.getArgument(0))
                                                                && r.getSku()
                                                                        .equals(
                                                                                call.getArgument(
                                                                                        1)))
                                        .findFirst());
        when(holds.save(any()))
                .thenAnswer(
                        call -> {
                            com.example.omp.inventory.domain.Reservation r = call.getArgument(0);
                            ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
                            ReflectionTestUtils.setField(r, "createdAt", Instant.now());
                            ReflectionTestUtils.setField(r, "updatedAt", Instant.now());
                            saved.put(r.getId(), r);
                            return r;
                        });
        context = new AnnotationConfigApplicationContext();
        context.registerBean(GrpcExceptionAdvice.class);
        context.registerBean(GrpcAdviceDiscoverer.class);
        context.registerBean(GrpcExceptionHandlerMethodResolver.class);
        context.registerBean(GrpcAdviceExceptionHandler.class);
        context.registerBean(GrpcExceptionInterceptor.class);
        context.refresh();
        var reservations = new ReservationHandler(holds, stocks, events);
        stream = new StockUpdateStreamHandler(stocks);
        var service =
                new InventoryServiceImpl(
                        new StockQueryHandler(stocks),
                        reservations,
                        new BulkOperationsHandler(reservations),
                        stream);
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                ServerInterceptors.intercept(
                                        service, context.getBean(GrpcExceptionInterceptor.class)))
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client =
                InventoryServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
        if (context != null) context.close();
    }

    @Test
    void stockQueriesDistinguishAvailabilityFromMissingStock() {
        var result =
                client.getStockLevel(
                        GetStockLevelRequest.newBuilder()
                                .setSku("SKU")
                                .setWarehouseId("WH")
                                .build());
        assertThat(result.getQuantityAvailable()).isEqualTo(10);
        assertThat(result.getLastUpdatedEpochMillis())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
        var query =
                CheckAvailabilityRequest.newBuilder()
                        .setSku("SKU")
                        .setWarehouseId("WH")
                        .setRequestedQuantity(11)
                        .build();
        assertThat(client.checkAvailability(query).getAvailable()).isFalse();
        assertThat(
                        client.checkAvailability(query.toBuilder().setRequestedQuantity(10).build())
                                .getAvailable())
                .isTrue();
        assertStatus(
                () ->
                        client.getStockLevel(
                                GetStockLevelRequest.newBuilder().setSku("UNKNOWN").build()),
                Status.Code.NOT_FOUND);
        assertStatus(
                () -> client.checkAvailability(query.toBuilder().setSku("UNKNOWN").build()),
                Status.Code.NOT_FOUND);
    }

    @Test
    void reserveReadCommitAndReplayMaintainAConsistentHold() {
        var request = request(3);
        var hold = client.reserveStock(request);
        assertThat(hold.getQuantity()).isEqualTo(3);
        assertThat(client.reserveStock(request).getId()).isEqualTo(hold.getId());
        assertThat(
                        client.getReservation(
                                GetReservationRequest.newBuilder()
                                        .setReservationId(hold.getId())
                                        .build()))
                .isEqualTo(hold);
        var commit =
                CommitReservationRequest.newBuilder()
                        .setReservationId(hold.getId())
                        .setReason("Shipped")
                        .build();
        assertThat(client.commitReservation(commit).getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_COMMITTED);
        client.commitReservation(commit);
        assertThat(stock.getQuantityOnHand()).isEqualTo(7);
        assertThat(stock.getQuantityReserved()).isZero();
        verify(events, times(1)).publishReservationCreated(any());
        verify(events, times(1)).publishReservationCommitted(any(), eq("Shipped"));
        assertStatus(
                () ->
                        client.releaseReservation(
                                ReleaseReservationRequest.newBuilder()
                                        .setReservationId(hold.getId())
                                        .build()),
                Status.Code.FAILED_PRECONDITION);
        assertStatus(() -> client.reserveStock(request), Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void releaseAndItsReplayDoNotChangePhysicalStock() {
        var hold = client.reserveStock(request(4));
        var release =
                ReleaseReservationRequest.newBuilder()
                        .setReservationId(hold.getId())
                        .setReason("Cancelled")
                        .build();
        assertThat(client.releaseReservation(release).getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
        client.releaseReservation(release);
        assertThat(stock.getQuantityOnHand()).isEqualTo(10);
        assertThat(stock.getQuantityReserved()).isZero();
        verify(events, times(1)).publishReservationReleased(any(), eq("Cancelled"));
        assertStatus(
                () ->
                        client.commitReservation(
                                CommitReservationRequest.newBuilder()
                                        .setReservationId(hold.getId())
                                        .build()),
                Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void invalidAndMissingReservationsUseStableGrpcStatuses() {
        assertStatus(() -> client.reserveStock(request(11)), Status.Code.FAILED_PRECONDITION);
        assertStatus(() -> client.reserveStock(request(0)), Status.Code.INVALID_ARGUMENT);
        assertStatus(
                () -> client.reserveStock(request(1).toBuilder().setSku("UNKNOWN").build()),
                Status.Code.NOT_FOUND);
        assertStatus(
                () ->
                        client.getReservation(
                                GetReservationRequest.newBuilder().setReservationId("bad").build()),
                Status.Code.INVALID_ARGUMENT);
        String missing = UUID.randomUUID().toString();
        assertStatus(
                () ->
                        client.releaseReservation(
                                ReleaseReservationRequest.newBuilder()
                                        .setReservationId(missing)
                                        .build()),
                Status.Code.NOT_FOUND);
        assertStatus(
                () ->
                        client.commitReservation(
                                CommitReservationRequest.newBuilder()
                                        .setReservationId(missing)
                                        .build()),
                Status.Code.NOT_FOUND);
        assertStatus(
                () ->
                        client.getReservation(
                                GetReservationRequest.newBuilder()
                                        .setReservationId(missing)
                                        .build()),
                Status.Code.NOT_FOUND);
        when(stocks.findBySkuAndWarehouseId("PRIVATE", "WH"))
                .thenThrow(new RuntimeException("private connection details"));
        assertThatThrownBy(
                        () ->
                                client.getStockLevel(
                                        GetStockLevelRequest.newBuilder()
                                                .setSku("PRIVATE")
                                                .setWarehouseId("WH")
                                                .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        error -> {
                            assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                            assertThat(error.getStatus().getDescription())
                                    .doesNotContain("private");
                        });
    }

    @Test
    void generatedClientStreamingReturnsOneResponseAfterCompletion() {
        var response = StreamRecorder.<BulkReserveResponse>create();
        var input = InventoryServiceGrpc.newStub(channel).bulkReserve(response);
        input.onNext(
                BulkReserveItem.newBuilder()
                        .setSku("SKU")
                        .setWarehouseId("WH")
                        .setQuantity(2)
                        .setOrderId(UUID.randomUUID().toString())
                        .build());
        assertThat(response.getValues()).isEmpty();
        input.onCompleted();
        assertThat(response.getError()).isNull();
        assertThat(response.getValues())
                .singleElement()
                .satisfies(r -> assertThat(r.getReservationsCount()).isEqualTo(1));
    }

    @Test
    void serverStreamingSendsSnapshotsUpdatesAndCleansUpCancellation() {
        try (var cancellation = Context.current().withCancellation()) {
            var response = StreamRecorder.<StockUpdate>create();
            cancellation.run(
                    () ->
                            InventoryServiceGrpc.newStub(channel)
                                    .streamStockUpdates(
                                            StreamStockUpdatesRequest.newBuilder()
                                                    .addSkus("SKU")
                                                    .setWarehouseId("WH")
                                                    .build(),
                                            response));
            assertThat(response.getValues()).hasSize(1);
            stock.reserve(2);
            stream.publishUpdate(stock, StockUpdateReason.STOCK_UPDATE_REASON_ADJUSTMENT);
            assertThat(response.getValues()).hasSize(2);
            assertThat(response.getValues().getLast().getQuantityAvailable()).isEqualTo(8);
            cancellation.cancel(null);
            assertThat(Status.fromThrowable(response.getError()).getCode())
                    .isEqualTo(Status.Code.CANCELLED);
            stream.publishUpdate(stock, StockUpdateReason.STOCK_UPDATE_REASON_ADJUSTMENT);
            assertThat(response.getValues()).hasSize(2);
        }
    }

    private ReserveStockRequest request(int quantity) {
        return ReserveStockRequest.newBuilder()
                .setSku("SKU")
                .setWarehouseId("WH")
                .setOrderId(UUID.randomUUID().toString())
                .setQuantity(quantity)
                .build();
    }

    private void assertStatus(Runnable call, Status.Code code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        error -> assertThat(error.getStatus().getCode()).isEqualTo(code));
    }
}
