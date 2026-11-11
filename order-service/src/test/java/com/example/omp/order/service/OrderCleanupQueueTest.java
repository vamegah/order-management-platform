package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.PaymentClient;
import com.example.omp.order.clients.grpc.interceptor.ServiceTokenScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class OrderCleanupQueueTest {
    @Test
    void cancellationCleanupUsesProjectedIdentityInsteadOfTheRequestJwt(
            @TempDir java.nio.file.Path directory) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var inventory = mock(InventoryClient.class);
        var payments = mock(PaymentClient.class);
        var metrics = new SimpleMeterRegistry();
        UUID order = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        var pending =
                new com.example.omp.order.clients.grpc.dto.PaymentIntentDto(
                        payment,
                        order,
                        UUID.randomUUID(),
                        "10",
                        "USD",
                        "PAYMENT_STATUS_PENDING",
                        null,
                        0,
                        0);
        var cancelled =
                new com.example.omp.order.clients.grpc.dto.PaymentIntentDto(
                        payment,
                        order,
                        pending.customerId(),
                        "10",
                        "USD",
                        "PAYMENT_STATUS_CANCELLED",
                        null,
                        0,
                        0);
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<OrderCleanupQueue.Task>>any(),
                        any(),
                        eq(order),
                        eq(100)))
                .thenReturn(
                        List.of(new OrderCleanupQueue.Task(taskId, order, "PAYMENT", payment, 1)));
        when(payments.get(payment))
                .thenAnswer(
                        invocation -> {
                            assertThat(ServiceTokenScope.isActive()).isTrue();
                            return pending;
                        });
        when(payments.cancel(payment.toString(), "Order cleanup"))
                .thenAnswer(
                        invocation -> {
                            assertThat(ServiceTokenScope.isActive()).isTrue();
                            return cancelled;
                        });
        when(jdbc.update(contains("completed = true"), eq(taskId), any())).thenReturn(1);
        var tokenFile = directory.resolve("access.jwt");
        java.nio.file.Files.writeString(tokenFile, "header.payload.signature");
        var queue =
                new OrderCleanupQueue(
                        jdbc, new TransactionTemplate(manager), inventory, payments, metrics);
        ReflectionTestUtils.setField(queue, "tokenFile", tokenFile.toString());
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                Jwt.withTokenValue("customer-token")
                                        .header("alg", "RS256")
                                        .subject("customer")
                                        .build()));

        try {
            assertThat(queue.retryOrder(order, 100)).isEqualTo(1);
            assertThat(ServiceTokenScope.isActive()).isFalse();
            verify(payments).cancel(payment.toString(), "Order cleanup");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void failedReleaseStaysPendingAndDoesNotBlockOtherResources() {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var inventory = mock(InventoryClient.class);
        var payments = mock(PaymentClient.class);
        var metrics = new SimpleMeterRegistry();
        UUID order = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID failedTask = UUID.randomUUID();
        UUID completedTask = UUID.randomUUID();
        var pending =
                new com.example.omp.order.clients.grpc.dto.PaymentIntentDto(
                        payment,
                        order,
                        UUID.randomUUID(),
                        "10",
                        "USD",
                        "PAYMENT_STATUS_PENDING",
                        null,
                        0,
                        0);
        var cancelled =
                new com.example.omp.order.clients.grpc.dto.PaymentIntentDto(
                        payment,
                        order,
                        pending.customerId(),
                        "10",
                        "USD",
                        "PAYMENT_STATUS_CANCELLED",
                        null,
                        0,
                        0);
        when(payments.get(payment)).thenReturn(pending);
        when(payments.cancel(payment.toString(), "Order cleanup")).thenReturn(cancelled);
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<OrderCleanupQueue.Task>>any(),
                        any(),
                        eq(order),
                        eq(100)))
                .thenReturn(
                        List.of(
                                new OrderCleanupQueue.Task(
                                        failedTask, order, "RESERVATION", reservation, 1),
                                new OrderCleanupQueue.Task(
                                        completedTask, order, "PAYMENT", payment, 1)));
        when(inventory.get(reservation))
                .thenReturn(
                        new com.example.omp.order.clients.grpc.dto.ReservationDto(
                                reservation,
                                "SKU",
                                "WH-1",
                                1,
                                order,
                                "RESERVATION_STATUS_ACTIVE",
                                0,
                                0));
        when(inventory.release(reservation.toString()))
                .thenThrow(new IllegalStateException("secret remote details"));
        when(jdbc.update(contains("completed = true"), eq(completedTask), any())).thenReturn(1);
        var queue =
                new OrderCleanupQueue(
                        jdbc, new TransactionTemplate(manager), inventory, payments, metrics);
        assertThat(queue.retryOrder(order, 100)).isEqualTo(1);
        verify(jdbc)
                .update(
                        contains("next_attempt_at = now()"),
                        eq("IllegalStateException"),
                        eq(failedTask),
                        any());
        verify(jdbc, never()).update(contains("completed = true"), eq(failedTask), any());
        var ordered = inOrder(manager, inventory, payments);
        ordered.verify(manager).commit(any());
        ordered.verify(inventory).release(reservation.toString());
        ordered.verify(payments).cancel(payment.toString(), "Order cleanup");
        assertThat(metrics.get("order.cleanup.failures").counter().count()).isEqualTo(1);
        assertThat(metrics.get("order.cleanup.completed").counter().count()).isEqualTo(1);
    }

    @Test
    void boundsWorkBeforeAccessingDatabase() {
        var jdbc = mock(JdbcTemplate.class);
        var queue =
                new OrderCleanupQueue(
                        jdbc,
                        mock(TransactionTemplate.class),
                        mock(InventoryClient.class),
                        mock(PaymentClient.class),
                        new SimpleMeterRegistry());
        assertThatThrownBy(() -> queue.retryOrder(UUID.randomUUID(), 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queue.enqueue(UUID.randomUUID(), "UNKNOWN", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
}
