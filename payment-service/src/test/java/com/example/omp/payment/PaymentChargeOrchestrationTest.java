package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.payment.domain.*;
import com.example.omp.payment.metrics.PaymentMetrics;
import com.example.omp.payment.provider.*;
import com.example.omp.payment.service.*;
import com.example.omp.shared.domain.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentChargeOrchestrationTest {
    final UUID id = UUID.randomUUID(), order = UUID.randomUUID(), customer = UUID.randomUUID();
    final PaymentTransactionalOps ops = mock(PaymentTransactionalOps.class);
    final PaymentProvider provider = mock(PaymentProvider.class);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final PaymentApplicationService service =
            new PaymentApplicationService(ops, provider, new PaymentMetrics(registry));
    final PaymentApplicationService.ChargeCommand command =
            new PaymentApplicationService.ChargeCommand(
                    order, customer, 1500, "USD", "business-key", "pm_bound");

    PaymentChargeOrchestrationTest() {
        when(provider.providerName()).thenReturn("stripe");
    }

    Payment payment(String status) {
        var p = new Payment(order, customer, Money.usd("15"), "business-key");
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", id);
        p.setProvider("stripe");
        p.setPaymentMethodId("pm_bound");
        p.setProviderReference("pi_bound");
        if (status.equals("confirmed")) {
            p.markAuthorized("pi_bound");
            p.markCaptured();
        } else if (status.equals("declined"))
            p.markFailed("card_declined", "Payment method declined");
        return p;
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirmed", "declined", "pending", "requires_action", "error"})
    void committedIntentPrecedesProviderIoAndOutcomePreservesCorrelation(String expected) {
        var input = payment("pending");
        var applied = payment(expected);
        ProviderChargeResult response =
                switch (expected) {
                    case "confirmed" -> ProviderChargeResult.succeeded("pi_bound");
                    case "declined" ->
                            ProviderChargeResult.declined(
                                    "card_declined", "Payment method declined");
                    case "requires_action" ->
                            ProviderChargeResult.requiresSdk("pi_bound", "pi_bound_secret_private");
                    case "pending" -> ProviderChargeResult.pending("pi_bound");
                    default -> ProviderChargeResult.error("Unresolved provider reply");
                };
        when(ops.createOrFindPending(command, "stripe")).thenReturn(input);
        when(provider.charge(any())).thenReturn(response);
        when(ops.applyChargeResult(id, response)).thenReturn(applied);
        var result = service.charge(command);
        assertThat(result.result()).isEqualTo(expected);
        assertThat(result.paymentId()).isEqualTo(id);
        var sequence = inOrder(ops, provider);
        sequence.verify(ops).createOrFindPending(command, "stripe");
        sequence.verify(provider)
                .charge(
                        argThat(
                                request ->
                                        request.paymentMethodId().equals("pm_bound")
                                                && request.providerReference().equals("pi_bound")
                                                && request.customerId().equals(customer)));
        sequence.verify(ops).applyChargeResult(id, response);
        if (expected.equals("requires_action")) {
            assertThat(result.clientSecret()).isEqualTo("pi_bound_secret_private");
            assertThat(result.toString()).doesNotContain("secret_private");
        }
        assertThat(registry.get("payment_provider_charge_duration_seconds").timer().count())
                .isEqualTo(1);
    }

    @Test
    void aTerminalReplayDoesNotCallTheProvider() {
        when(ops.createOrFindPending(command, "stripe")).thenReturn(payment("confirmed"));
        assertThat(service.charge(command).result()).isEqualTo("confirmed");
        verify(provider, never()).charge(any());
        verify(ops, never()).applyChargeResult(any(), any());
    }

    @Test
    void aCommittedCallbackWinsOverALostHttpReply() {
        when(ops.createOrFindPending(command, "stripe")).thenReturn(payment("pending"));
        when(provider.charge(any())).thenThrow(new IllegalStateException("private-provider-body"));
        when(ops.applyChargeResult(eq(id), any())).thenReturn(payment("confirmed"));
        var result = service.charge(command);
        assertThat(result.result()).isEqualTo("confirmed");
        assertThat(result.toString()).doesNotContain("private-provider-body");
    }

    @Test
    void ownerConfirmationBindsTheExistingIntentWithoutCreatingAnotherPayment() {
        when(ops.bindPaymentMethod(id, customer, "pm_bound", "stripe"))
                .thenReturn(payment("pending"));
        var response = ProviderChargeResult.pending("pi_bound");
        when(provider.charge(any())).thenReturn(response);
        when(ops.applyChargeResult(id, response)).thenReturn(payment("pending"));
        assertThat(service.confirm(id, customer, "pm_bound").result()).isEqualTo("pending");
        verify(ops, never()).createOrFindPending(any(), any());
        when(ops.findById(id)).thenReturn(payment("pending"));
        assertThat(service.get(id).getId()).isEqualTo(id);
    }
}
