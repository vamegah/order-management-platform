package com.example.omp.payment.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.metrics.PaymentMetrics;
import com.example.omp.payment.provider.*;
import com.example.omp.payment.service.*;
import com.example.omp.shared.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP serialization and provider verification contracts; security-chain acceptance is separate.
 */
class PaymentHttpContractTest {
    private final PaymentApplicationService payments = mock(PaymentApplicationService.class);
    private final PaymentProvider provider = mock(PaymentProvider.class);
    private final PaymentProviderEventService events = mock(PaymentProviderEventService.class);
    private final PaymentMetrics metrics = mock(PaymentMetrics.class);
    private final UUID owner = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void start() {
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new PaymentController(payments),
                                new StripeWebhookController(provider, events, metrics))
                        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                        .build();
        var jwt =
                Jwt.withTokenValue("test")
                        .header("alg", "none")
                        .subject("owner")
                        .claim("customer_id", owner.toString())
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        when(provider.providerName()).thenReturn("stripe");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({"confirmed,200", "declined,402", "requires_action,202", "error,502", "pending,200"})
    void chargeOutcomesKeepTheirHttpStatusAndVerifiedCustomerIdentity(String outcome, int status)
            throws Exception {
        UUID id = UUID.randomUUID(), order = UUID.randomUUID();
        when(payments.charge(any()))
                .thenReturn(
                        new PaymentApplicationService.ChargeOutcome(
                                outcome,
                                id,
                                "pi",
                                "decline",
                                "message",
                                "https://provider.example/action"));
        mvc.perform(
                        post("/api/v1/payments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"orderId\":\""
                                                + order
                                                + "\",\"amountMinor\":1250,\"currency\":\"USD\",\"idempotencyKey\":\"stable-key\"}"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.paymentId").value(id.toString()))
                .andExpect(jsonPath("$.result").value(outcome))
                .andExpect(
                        jsonPath("$.requiresActionUrl").value("https://provider.example/action"));
        verify(payments)
                .charge(
                        new PaymentApplicationService.ChargeCommand(
                                order, owner, 1250, "USD", "stable-key"));
    }

    @Test
    void readsSerializeMoneyLifecycleTimestampsAndCorrelationFields() throws Exception {
        UUID id = UUID.randomUUID();
        var payment = new Payment(UUID.randomUUID(), owner, Money.usd("12.50"));
        ReflectionTestUtils.setField(payment, "id", id);
        ReflectionTestUtils.setField(payment, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(payment, "provider", "noop");
        payment.confirm("pi");
        payment.markCaptured();
        when(payments.get(id)).thenReturn(payment);
        mvc.perform(get("/api/v1/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountMinor").value(1250))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.customerId").value(owner.toString()))
                .andExpect(jsonPath("$.providerReference").value("pi"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.capturedAt").isNotEmpty())
                .andExpect(jsonPath("$.failedAt").isEmpty());
    }

    @ParameterizedTest
    @EnumSource(PaymentProviderEventService.ProcessResult.class)
    void authenticatedProviderEventsPreserveRawBodyAndAcknowledgeProcessingOutcome(
            PaymentProviderEventService.ProcessResult result) throws Exception {
        String raw = "{ \"id\": \"evt\", \"note\": \"café\" }\n";
        var event =
                new ProviderWebhookEvent("evt", "updated", new ObjectMapper().readTree(raw), "pi");
        when(provider.verifyWebhook(eq(raw), anyMap()))
                .thenReturn(ProviderWebhookVerification.valid(event));
        when(events.process("stripe", event)).thenReturn(result);
        mvc.perform(
                        post("/api/v1/payments/webhooks/stripe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .characterEncoding("UTF-8")
                                .header("Stripe-Signature", "signed-exact-bytes")
                                .content(raw))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(result.name().toLowerCase()));
        verify(provider)
                .verifyWebhook(
                        eq(raw),
                        argThat(
                                headers ->
                                        "signed-exact-bytes"
                                                .equals(
                                                        headers.entrySet().stream()
                                                                .filter(
                                                                        e ->
                                                                                e.getKey()
                                                                                        .equalsIgnoreCase(
                                                                                                "Stripe-Signature"))
                                                                .map(java.util.Map.Entry::getValue)
                                                                .findFirst()
                                                                .orElse(""))));
        verify(metrics).recordWebhook("stripe", result.name().toLowerCase());
    }

    @Test
    void rejectsUnknownProvidersAndInvalidSignaturesBeforeApplyingEvents() throws Exception {
        mvc.perform(
                        post("/api/v1/payments/webhooks/other")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isNotFound());
        verify(provider, never()).verifyWebhook(anyString(), anyMap());
        when(provider.verifyWebhook(anyString(), anyMap()))
                .thenReturn(ProviderWebhookVerification.invalid("bad_signature"));
        mvc.perform(
                        post("/api/v1/payments/webhooks/stripe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(events);
        verify(metrics).recordWebhookSignatureFailure();
    }

    @Test
    void processingFailuresAreRedactedAndRemainRetryableByTheProvider() throws Exception {
        var event =
                new ProviderWebhookEvent(
                        "evt", "updated", new ObjectMapper().createObjectNode(), "pi");
        when(provider.verifyWebhook(anyString(), anyMap()))
                .thenReturn(ProviderWebhookVerification.valid(event));
        when(events.process(anyString(), any()))
                .thenThrow(new RuntimeException("private database details"));
        mvc.perform(
                        post("/api/v1/payments/webhooks/stripe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("processing_failed"));
        verify(metrics).recordWebhook("stripe", "error");
    }
}
