package com.example.omp.payment;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.omp.payment.config.PaymentAccess;
import com.example.omp.payment.domain.*;
import com.example.omp.payment.provider.PaymentProvider;
import com.example.omp.payment.repository.PaymentRepository;
import com.example.omp.payment.security.PaymentSecurityConfig;
import com.example.omp.payment.service.*;
import com.example.omp.payment.web.*;
import com.example.omp.shared.domain.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Production HTTP filter chain and actual method policy; JWT decoding and persistence controlled.
 */
class RefundRecoveryWebTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    @Import({
        PaymentSecurityConfig.class,
        RefundRecoveryController.class,
        PaymentExceptionHandler.class
    })
    static class Beans {
        @Bean
        JwtDecoder decoder() {
            return mock(JwtDecoder.class);
        }

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
        RefundRecoveryService recovery(RefundStore store, PaymentProvider provider) {
            return new RefundRecoveryService(store, provider, new SimpleMeterRegistry());
        }
    }

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private RefundStore store;
    private final UUID paymentId = UUID.randomUUID(),
            refundId = UUID.randomUUID(),
            owner = UUID.randomUUID();
    private Refund refund;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Beans.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        store = context.getBean(RefundStore.class);
        Payment payment = new Payment(UUID.randomUUID(), owner, Money.usd("10"));
        ReflectionTestUtils.setField(payment, "id", paymentId);
        refund = new Refund(payment, Money.usd("4"), "requested", "private-key");
        ReflectionTestUtils.setField(refund, "id", refundId);
        ReflectionTestUtils.setField(refund, "createdAt", Instant.now());
        when(context.getBean(PaymentRepository.class).findById(paymentId))
                .thenReturn(Optional.of(payment));
        when(context.getBean(PaymentProvider.class).providerName()).thenReturn("stripe");
        when(store.read(paymentId, refundId)).thenReturn(refund);
    }

    @AfterEach
    void close() {
        context.close();
    }

    private String path() {
        return "/api/v1/payments/" + paymentId + "/refunds/" + refundId;
    }

    @Test
    void anonymousCannotInspectOrRetry() throws Exception {
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        mvc.perform(post(path() + "/retry")).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }

    @Test
    void ownerCanInspectAuditStateWithoutProviderKeyDisclosure() throws Exception {
        mvc.perform(
                        get(path())
                                .with(
                                        jwt().jwt(
                                                        token ->
                                                                token.claim(
                                                                        "customer_id",
                                                                        owner.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.id").value(refundId.toString()))
                .andExpect(jsonPath("$.refund.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.nextAttemptAt").isString())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "private-key"))))
                .andExpect(jsonPath("$.refund.providerKey").doesNotExist());
        verify(store).read(paymentId, refundId);
    }

    @Test
    void strangerCannotInspectAnotherOwnersRefund() throws Exception {
        mvc.perform(
                        get(path())
                                .with(
                                        jwt().jwt(
                                                        token ->
                                                                token.claim(
                                                                        "customer_id",
                                                                        UUID.randomUUID()
                                                                                .toString()))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "worker"})
    void ownerAndRecoveryScopeCannotUseAdministratorAction(String identity) throws Exception {
        var token = jwt().jwt(builder -> builder.claim("customer_id", owner.toString()));
        if (identity.equals("worker"))
            token.authorities(new SimpleGrantedAuthority("SCOPE_order.reconcile"));
        mvc.perform(post(path() + "/retry").with(token)).andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "COMPLETED", "FAILED"})
    void administratorRetryReturnsThePersistedOutcome(String outcome) throws Exception {
        if (outcome.equals("COMPLETED")) refund.markCompleted();
        if (outcome.equals("FAILED")) refund.markFailed(null);
        var response =
                mvc.perform(
                        post(path() + "/retry")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_admin"))));
        response.andExpect(
                        status().is(
                                        outcome.equals("PENDING")
                                                ? 202
                                                : outcome.equals("FAILED") ? 422 : 200))
                .andExpect(jsonPath("$.refund.status").value(outcome));
        if (outcome.equals("PENDING")) response.andExpect(header().string("Retry-After", "60"));
        verify(store).claimRecovery(paymentId, refundId, "stripe", true);
    }

    @Test
    void realmAdministratorCanInspectAndMissingRefundIs404() throws Exception {
        when(store.read(paymentId, refundId))
                .thenThrow(
                        new com.example.omp.payment.error.PaymentNotFoundException(
                                "Refund not found"));
        mvc.perform(
                        get(path())
                                .with(
                                        jwt().jwt(
                                                        token ->
                                                                token.claim(
                                                                        "realm_access",
                                                                        Map.of(
                                                                                "roles",
                                                                                List.of(
                                                                                        "admin"))))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Payment not found"));
    }
}
