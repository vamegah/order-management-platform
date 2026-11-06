package com.example.omp.customer.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.customer.service.CustomerService;
import com.example.omp.shared.web.pagination.PageableResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(CustomerBatchAuthorizationTest.Config.class)
class CustomerBatchAuthorizationTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        CustomerService customers() {
            return mock(CustomerService.class);
        }

        @Bean
        CustomerController controller(CustomerService service) {
            return new CustomerController(service, mock(PageableResolver.class));
        }
    }

    @Autowired CustomerController controller;
    @Autowired CustomerService service;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        reset(service);
    }

    private void authenticate(UUID customer, String role) {
        var jwt =
                Jwt.withTokenValue("token")
                        .header("alg", "none")
                        .subject("user")
                        .claim("customer_id", customer.toString())
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void customerCanReadOnlyTheirOwnBatch() {
        UUID own = UUID.randomUUID(), other = UUID.randomUUID();
        authenticate(own, "customer");
        controller.batch(List.of(own));
        verify(service).getBatch(List.of(own));
        assertThatThrownBy(() -> controller.batch(List.of(own, other)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.batch(List.of(other)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoMoreInteractions(service);
    }

    @Test
    void adminCanReadMultipleCustomers() {
        authenticate(UUID.randomUUID(), "admin");
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        controller.batch(ids);
        verify(service).getBatch(ids);
    }
}
