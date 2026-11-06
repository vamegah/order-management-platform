package com.example.omp.payment;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.omp.payment.domain.*;
import com.example.omp.payment.provider.PaymentProvider;
import com.example.omp.payment.service.*;
import com.example.omp.payment.web.RefundReconciliationController;
import com.example.omp.shared.domain.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/** Production filter chain plus real method policy; provider and persistence controlled. */
class RefundReconciliationWebTest {
    @Configuration
    @Import({RefundRecoveryWebTest.Beans.class, RefundReconciliationController.class})
    static class Beans {
        @Bean
        RefundReconciliationStore reconciliationStore() {
            return mock(RefundReconciliationStore.class);
        }

        @Bean
        RefundReconciliationService reconciliationService(
                RefundReconciliationStore store, PaymentProvider provider) {
            return new RefundReconciliationService(store, provider, new SimpleMeterRegistry());
        }
    }

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private RefundReconciliationStore store;
    private final UUID paymentId = UUID.randomUUID(),
            refundId = UUID.randomUUID(),
            auditId = UUID.randomUUID();
    private Refund refund;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Beans.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        store = context.getBean(RefundReconciliationStore.class);
        var payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), Money.usd("10"));
        ReflectionTestUtils.setField(payment, "id", paymentId);
        refund = new Refund(payment, Money.usd("4"), "original", "private-key");
        ReflectionTestUtils.setField(refund, "id", refundId);
        ReflectionTestUtils.setField(refund, "createdAt", Instant.now());
        when(context.getBean(PaymentProvider.class).providerName()).thenReturn("stripe");
        when(store.begin(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RefundReconciliationStore.Claim(auditId, null));
        when(store.read(paymentId, refundId, auditId)).thenReturn(result("BUSY"));
        when(store.history(paymentId, refundId)).thenReturn(List.of(result("BUSY").audit()));
    }

    @AfterEach
    void close() {
        context.close();
    }

    private String path() {
        return "/api/v1/payments/" + paymentId + "/refunds/" + refundId + "/reconciliation";
    }

    private String body() {
        return "{\"providerReference\":\"re_candidate\",\"reason\":\"ledger review\"}";
    }

    @Test
    void anonymousCannotInspectAuditOrReconcile() throws Exception {
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"customer", "worker"})
    void customersAndRecoveryScopeCannotUseTheOperatorEndpoints(String identity) throws Exception {
        var token =
                jwt().authorities(
                                new SimpleGrantedAuthority(
                                        identity.equals("worker")
                                                ? "SCOPE_order.reconcile"
                                                : "ROLE_customer"));
        mvc.perform(get(path()).with(token)).andExpect(status().isForbidden());
        mvc.perform(
                        post(path())
                                .with(token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUSY", "BLOCKED", "COMPLETED", "FAILED"})
    void administratorRequestRecordsJwtActorAndReturnsExplicitOutcome(String outcome)
            throws Exception {
        if (outcome.equals("COMPLETED")) refund.markCompleted();
        if (outcome.equals("FAILED")) refund.markFailed(null);
        when(store.read(paymentId, refundId, auditId)).thenReturn(result(outcome));
        var token =
                jwt().jwt(builder -> builder.subject("operator").issuer("https://issuer.example"))
                        .authorities(new SimpleGrantedAuthority("ROLE_admin"));
        var result =
                mvc.perform(
                        post(path())
                                .with(token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body()));
        result.andExpect(
                        status().is(
                                        switch (outcome) {
                                            case "BLOCKED" -> 409;
                                            case "COMPLETED" -> 200;
                                            case "FAILED" -> 422;
                                            default -> 202;
                                        }))
                .andExpect(jsonPath("$.audit.id").value(auditId.toString()))
                .andExpect(jsonPath("$.audit.outcome").value(outcome))
                .andExpect(jsonPath("$.recovery.refund.id").value(refundId.toString()))
                .andExpect(jsonPath("$.recovery.refund.idempotencyKey").doesNotExist());
        verify(store)
                .begin(
                        paymentId,
                        refundId,
                        "re_candidate",
                        "stripe",
                        "operator",
                        "https://issuer.example",
                        "ledger review");
    }

    @Test
    void realmAdministratorCanReadBoundedAuditHistory() throws Exception {
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].candidateReference").value("re_candidate"))
                .andExpect(jsonPath("$[0].actorSubject").value("operator"));
        verify(store).history(paymentId, refundId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reference", "reason", "long-reason", "nul-reason"})
    void malformedBodyCannotReachTheFinancialStore(String invalid) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        json.put("providerReference", invalid.equals("reference") ? "../refunds" : "re_candidate")
                .put(
                        "reason",
                        switch (invalid) {
                            case "reason" -> " ";
                            case "long-reason" -> "x".repeat(501);
                            case "nul-reason" -> "review" + Character.toString(0);
                            default -> "review";
                        });
        mvc.perform(
                        post(path())
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_admin")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }

    private RefundReconciliationStore.Result result(String outcome) {
        return new RefundReconciliationStore.Result(
                refund,
                new RefundReconciliationStore.AuditView(
                        auditId,
                        refundId,
                        "re_candidate",
                        "operator",
                        "https://issuer.example",
                        "ledger review",
                        outcome,
                        null,
                        Instant.now().toString(),
                        Instant.now().toString()));
    }
}
