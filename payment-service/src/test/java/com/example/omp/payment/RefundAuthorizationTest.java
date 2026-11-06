package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.payment.config.PaymentAccess;
import com.example.omp.payment.domain.*;
import com.example.omp.payment.grpc.PaymentServiceImpl;
import com.example.omp.payment.grpc.handler.*;
import com.example.omp.payment.provider.PaymentProvider;
import com.example.omp.payment.repository.PaymentRepository;
import com.example.omp.payment.service.*;
import com.example.omp.proto.payment.*;
import com.example.omp.shared.domain.Money;
import io.grpc.*;
import io.grpc.inprocess.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

class RefundAuthorizationTest {
    @Configuration
    @EnableMethodSecurity
    static class Beans {
        @Bean
        PaymentRepository payments() {
            return mock(PaymentRepository.class);
        }

        @Bean(name = "paymentAccess")
        PaymentAccess access(PaymentRepository payments) {
            return new PaymentAccess(payments);
        }

        @Bean
        RefundStore store() {
            return mock(RefundStore.class);
        }

        @Bean
        PaymentProvider provider() {
            return mock(PaymentProvider.class);
        }

        @Bean
        RefundApplicationService service(RefundStore store, PaymentProvider provider) {
            return new RefundApplicationService(store, provider);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "admin", "worker", "stranger", "anonymous"})
    void actualGrpcRefundEnforcesSharedMethodPolicy(String identity) throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Beans.class)) {
            UUID paymentId = UUID.randomUUID(), owner = UUID.randomUUID();
            var payment =
                    new com.example.omp.payment.domain.Payment(
                            UUID.randomUUID(), owner, Money.usd("10"));
            ReflectionTestUtils.setField(payment, "id", paymentId);
            when(context.getBean(PaymentRepository.class).findById(paymentId))
                    .thenReturn(Optional.of(payment));
            var store = context.getBean(RefundStore.class);
            when(store.prepare(any(), any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("reached authorized workflow"));
            var authorities =
                    identity.equals("worker")
                            ? List.of(new SimpleGrantedAuthority("SCOPE_order.reconcile"))
                            : identity.equals("admin")
                                    ? List.of(new SimpleGrantedAuthority("ROLE_admin"))
                                    : List.<SimpleGrantedAuthority>of();
            if (!identity.equals("anonymous")) {
                var jwt =
                        Jwt.withTokenValue("test")
                                .header("alg", "none")
                                .subject(identity)
                                .claim(
                                        "customer_id",
                                        identity.equals("owner")
                                                ? owner.toString()
                                                : UUID.randomUUID().toString())
                                .build();
                SecurityContextHolder.getContext()
                        .setAuthentication(new JwtAuthenticationToken(jwt, authorities));
            }
            String name = InProcessServerBuilder.generateName();
            var server =
                    InProcessServerBuilder.forName(name)
                            .directExecutor()
                            .addService(
                                    new PaymentServiceImpl(
                                            mock(PaymentIntentHandler.class),
                                            mock(PaymentQueryHandler.class),
                                            new RefundHandler(
                                                    context.getBean(RefundApplicationService.class),
                                                    new MoneyConverter()),
                                            mock(PaymentEventStreamHandler.class)))
                            .build()
                            .start();
            var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
            try {
                Status.Code expected =
                        identity.equals("stranger")
                                ? Status.Code.PERMISSION_DENIED
                                : identity.equals("anonymous")
                                        ? Status.Code.UNAUTHENTICATED
                                        : Status.Code.INVALID_ARGUMENT;
                assertThatThrownBy(
                                () ->
                                        PaymentServiceGrpc.newBlockingStub(channel)
                                                .refundPayment(
                                                        RefundPaymentRequest.newBuilder()
                                                                .setPaymentId(paymentId.toString())
                                                                .setIdempotencyKey("key")
                                                                .build()))
                        .isInstanceOfSatisfying(
                                StatusRuntimeException.class,
                                e -> assertThat(e.getStatus().getCode()).isEqualTo(expected));
                if (expected == Status.Code.INVALID_ARGUMENT)
                    verify(store).prepare(any(), any(), any(), any(), any(), any());
                else verifyNoInteractions(store, context.getBean(PaymentProvider.class));
            } finally {
                channel.shutdownNow();
                server.shutdownNow();
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
