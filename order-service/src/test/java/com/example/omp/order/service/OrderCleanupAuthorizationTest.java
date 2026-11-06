package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.web.OrderCleanupController;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

class OrderCleanupAuthorizationTest {
    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity
    static class ConfigurationUnderTest {
        @Bean
        OrderCleanupQueue queue() {
            return mock(OrderCleanupQueue.class);
        }

        @Bean
        OrderCleanupController controller(OrderCleanupQueue queue) {
            return new OrderCleanupController(queue);
        }
    }

    @Test
    void customerCannotRunCleanupForAnArbitraryOrder() {
        try (var context = new AnnotationConfigApplicationContext(ConfigurationUnderTest.class)) {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new TestingAuthenticationToken("customer", "", "ROLE_customer"));
            assertThatThrownBy(
                            () ->
                                    context.getBean(OrderCleanupController.class)
                                            .retry(UUID.randomUUID()))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(context.getBean(OrderCleanupQueue.class));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void adminCanRetryABoundedBatch() {
        try (var context = new AnnotationConfigApplicationContext(ConfigurationUnderTest.class)) {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new TestingAuthenticationToken("operator", "", "ROLE_admin"));
            UUID order = UUID.randomUUID();
            context.getBean(OrderCleanupController.class).retry(order);
            verify(context.getBean(OrderCleanupQueue.class)).retryOrder(order, 100);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
