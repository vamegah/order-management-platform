package com.example.omp.inventory.grpc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.inventory.error.InventoryNotFoundException;
import com.example.omp.inventory.grpc.handler.*;
import com.example.omp.proto.inventory.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

/** Observer callbacks must preserve stable error codes and redact handler/provider details. */
class CompensationRpcContractTest {
    private Server server;
    private ManagedChannel channel;
    private InventoryServiceGrpc.InventoryServiceBlockingStub client;
    private RuntimeException failure;
    private final UUID order = UUID.randomUUID();

    @BeforeEach
    void start() throws Exception {
        var handler = mock(ReservationHandler.class);
        when(handler.compensateOrder(any()))
                .thenAnswer(
                        call -> {
                            CompensateOrderRequest request = call.getArgument(0);
                            assertThat(request.getOrderId()).isEqualTo(order.toString());
                            if (failure != null) throw failure;
                            return FindReservationsByOrderResponse.getDefaultInstance();
                        });
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                new InventoryServiceImpl(
                                        mock(StockQueryHandler.class),
                                        handler,
                                        mock(BulkOperationsHandler.class),
                                        mock(StockUpdateStreamHandler.class)))
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client =
                InventoryServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private FindReservationsByOrderResponse call() {
        return client.compensateOrder(
                CompensateOrderRequest.newBuilder()
                        .setOrderId(order.toString())
                        .setReason("Cancel owned order")
                        .build());
    }

    @Test
    void anEmptyOwnedOrderCanReceiveAnAcknowledgedCompensationFence() {
        assertThat(call().getReservationsCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "AUTHENTICATION,UNAUTHENTICATED",
        "NESTED_AUTHENTICATION,UNAUTHENTICATED",
        "DENIED,PERMISSION_DENIED",
        "MISSING,NOT_FOUND",
        "BUSY,ABORTED",
        "ARGUMENT,INVALID_ARGUMENT",
        "STATE,FAILED_PRECONDITION",
        "UNKNOWN,INTERNAL"
    })
    void compensationErrorsHaveStableCodesAndNoPrivateDescriptions(String kind, Status.Code code) {
        failure =
                switch (kind) {
                    case "AUTHENTICATION" -> new BadCredentialsException("private-secret");
                    case "NESTED_AUTHENTICATION" ->
                            new RuntimeException(
                                    "private-secret",
                                    new BadCredentialsException("private-secret"));
                    case "DENIED" -> new AccessDeniedException("private-secret");
                    case "MISSING" -> new InventoryNotFoundException("private-secret");
                    case "BUSY" -> new OptimisticLockingFailureException("private-secret");
                    case "ARGUMENT" -> new IllegalArgumentException("private-secret");
                    case "STATE" -> new IllegalStateException("private-secret");
                    default -> new UnsupportedOperationException("private-secret");
                };
        assertThatThrownBy(this::call)
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        error -> {
                            assertThat(error.getStatus().getCode()).isEqualTo(code);
                            assertThat(error.getMessage()).doesNotContain("private-secret");
                        });
    }

    @Test
    void cyclicExceptionCausesCannotHangTheRpcCallback() {
        var first = new RuntimeException("private-secret");
        var second = new RuntimeException("private-secret", first);
        first.initCause(second);
        failure = first;
        assertThatThrownBy(this::call)
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        error -> {
                            assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                            assertThat(error.getMessage()).doesNotContain("private-secret");
                        });
    }
}
