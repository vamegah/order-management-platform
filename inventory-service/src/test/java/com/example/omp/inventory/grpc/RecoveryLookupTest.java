package com.example.omp.inventory.grpc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.inventory.grpc.handler.ReservationHandler;
import com.example.omp.inventory.repository.ReservationRepository;
import com.example.omp.proto.inventory.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

class RecoveryLookupTest {
    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity
    static class Beans {
        @Bean
        ReservationRepository repository() {
            return mock(ReservationRepository.class);
        }

        @Bean
        ReservationHandler handler(ReservationRepository repository) {
            return new ReservationHandler(
                    repository,
                    mock(com.example.omp.inventory.repository.StockLevelRepository.class),
                    mock(com.example.omp.inventory.events.InventoryEventPublisher.class));
        }
    }

    private AnnotationConfigApplicationContext context;
    private Server server;
    private ManagedChannel channel;
    private ReservationRepository repository;

    @BeforeEach
    void start() throws Exception {
        context = new AnnotationConfigApplicationContext(Beans.class);
        repository = context.getBean(ReservationRepository.class);
        var handler = context.getBean(ReservationHandler.class);
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                new InventoryServiceImpl(
                                        mock(
                                                com.example.omp.inventory.grpc.handler
                                                        .StockQueryHandler.class),
                                        handler,
                                        mock(
                                                com.example.omp.inventory.grpc.handler
                                                        .BulkOperationsHandler.class),
                                        mock(
                                                com.example.omp.inventory.grpc.handler
                                                        .StockUpdateStreamHandler.class)))
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
        context.close();
        SecurityContextHolder.clearContext();
    }

    private void identity(String authority) {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("identity", "", authority));
    }

    @Test
    void serviceScopeCanLookUpAnOrderWithoutCreatingResources() {
        identity("SCOPE_order.reconcile");
        UUID order = UUID.randomUUID();
        when(repository.findTop101ByOrderIdOrderByCreatedAtAsc(order)).thenReturn(List.of());
        var result =
                InventoryServiceGrpc.newBlockingStub(channel)
                        .findReservationsByOrder(
                                FindReservationsByOrderRequest.newBuilder()
                                        .setOrderId(order.toString())
                                        .build());
        assertThat(result.getReservationsCount()).isZero();
        verify(repository).findTop101ByOrderIdOrderByCreatedAtAsc(order);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void normalCustomerCannotEnumerateResources() {
        identity("SCOPE_openid");
        assertThatThrownBy(
                        () ->
                                InventoryServiceGrpc.newBlockingStub(channel)
                                        .findReservationsByOrder(
                                                FindReservationsByOrderRequest.newBuilder()
                                                        .setOrderId(UUID.randomUUID().toString())
                                                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        failure ->
                                assertThat(failure.getStatus().getCode())
                                        .isEqualTo(Status.Code.PERMISSION_DENIED));
        verifyNoInteractions(repository);
    }

    @Test
    void boundedLookupAndMalformedIdsHaveStableErrors() {
        identity("SCOPE_order.reconcile");
        UUID order = UUID.randomUUID();
        when(repository.findTop101ByOrderIdOrderByCreatedAtAsc(order))
                .thenReturn(
                        Collections.nCopies(
                                101, mock(com.example.omp.inventory.domain.Reservation.class)));
        assertThatThrownBy(
                        () ->
                                InventoryServiceGrpc.newBlockingStub(channel)
                                        .findReservationsByOrder(
                                                FindReservationsByOrderRequest.newBuilder()
                                                        .setOrderId(order.toString())
                                                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        failure ->
                                assertThat(failure.getStatus().getCode())
                                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
        assertThatThrownBy(
                        () ->
                                InventoryServiceGrpc.newBlockingStub(channel)
                                        .findReservationsByOrder(
                                                FindReservationsByOrderRequest.newBuilder()
                                                        .setOrderId("bad-id")
                                                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        failure ->
                                assertThat(failure.getStatus().getCode())
                                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }
}
