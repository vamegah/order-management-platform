package com.example.omp.payment.grpc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.payment.grpc.handler.PaymentQueryHandler;
import com.example.omp.payment.repository.PaymentRepository;
import com.example.omp.proto.payment.*;
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
        PaymentRepository repository() {
            return mock(PaymentRepository.class);
        }

        @Bean
        PaymentQueryHandler handler(PaymentRepository repository) {
            return new PaymentQueryHandler(repository);
        }
    }

    private AnnotationConfigApplicationContext context;
    private Server server;
    private ManagedChannel channel;
    private PaymentRepository repository;

    @BeforeEach
    void start() throws Exception {
        context = new AnnotationConfigApplicationContext(Beans.class);
        repository = context.getBean(PaymentRepository.class);
        var handler = context.getBean(PaymentQueryHandler.class);
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                new PaymentServiceImpl(
                                        mock(
                                                com.example.omp.payment.grpc.handler
                                                        .PaymentIntentHandler.class),
                                        handler,
                                        mock(
                                                com.example.omp.payment.grpc.handler.RefundHandler
                                                        .class),
                                        mock(
                                                com.example.omp.payment.grpc.handler
                                                        .PaymentEventStreamHandler.class)))
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
                PaymentServiceGrpc.newBlockingStub(channel)
                        .findPaymentsByOrder(
                                FindPaymentsByOrderRequest.newBuilder()
                                        .setOrderId(order.toString())
                                        .build());
        assertThat(result.getPaymentsCount()).isZero();
        verify(repository).findTop101ByOrderIdOrderByCreatedAtAsc(order);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void normalCustomerCannotEnumerateResources() {
        identity("SCOPE_openid");
        assertThatThrownBy(
                        () ->
                                PaymentServiceGrpc.newBlockingStub(channel)
                                        .findPaymentsByOrder(
                                                FindPaymentsByOrderRequest.newBuilder()
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
                                101, mock(com.example.omp.payment.domain.Payment.class)));
        assertThatThrownBy(
                        () ->
                                PaymentServiceGrpc.newBlockingStub(channel)
                                        .findPaymentsByOrder(
                                                FindPaymentsByOrderRequest.newBuilder()
                                                        .setOrderId(order.toString())
                                                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        failure ->
                                assertThat(failure.getStatus().getCode())
                                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
        assertThatThrownBy(
                        () ->
                                PaymentServiceGrpc.newBlockingStub(channel)
                                        .findPaymentsByOrder(
                                                FindPaymentsByOrderRequest.newBuilder()
                                                        .setOrderId("bad-id")
                                                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        failure ->
                                assertThat(failure.getStatus().getCode())
                                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }
}
