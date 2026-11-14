package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.outbox.OutboxWriter;
import com.example.omp.payment.provider.ProviderWebhookEvent;
import com.example.omp.payment.repository.PaymentProviderEventRepository;
import com.example.omp.payment.repository.PaymentRepository;
import com.example.omp.payment.service.*;
import com.example.omp.shared.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentIdempotencyTest {

    @Test
    void providerConfirmationNotifiesTheStreamOnceDespiteDeliveryReplay() {
        var receipts = mock(PaymentProviderEventRepository.class);
        var payments = mock(PaymentRepository.class);
        var outbox = mock(OutboxWriter.class);
        var streams = mock(com.example.omp.payment.grpc.handler.PaymentEventStreamHandler.class);
        var payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), Money.usd("10"));
        org.springframework.test.util.ReflectionTestUtils.setField(
                payment, "id", UUID.randomUUID());
        payment.setProvider("stripe");
        payment.setProviderReference("pi-1");
        when(receipts.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1, 0);
        when(receipts.findById(any()))
                .thenReturn(Optional.of(new com.example.omp.payment.domain.PaymentProviderEvent()));
        when(payments.lockByProviderReference("stripe", "pi-1")).thenReturn(Optional.of(payment));
        var service =
                new PaymentProviderEventService(
                        receipts, payments, outbox, mock(RefundStore.class), streams);
        var event =
                new ProviderWebhookEvent(
                        "event-1",
                        "payment_intent.succeeded",
                        new ObjectMapper().createObjectNode(),
                        "pi-1");
        assertThat(service.process("stripe", event))
                .isEqualTo(PaymentProviderEventService.ProcessResult.ACCEPTED);
        assertThat(service.process("stripe", event))
                .isEqualTo(PaymentProviderEventService.ProcessResult.DUPLICATE);
        verify(streams, times(1))
                .publishEvent(
                        payment,
                        com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_PENDING,
                        "provider confirmed");
        verify(outbox, times(1)).write(anyString(), eq(payment.getId()), any());
    }

    @Test
    void restAmountCannotOverflowDatabasePrecision() {
        var repository = mock(PaymentRepository.class);
        var outbox = mock(OutboxWriter.class);
        var lock = mock(PaymentCreationLock.class);
        var operations =
                new PaymentTransactionalOps(
                        repository,
                        outbox,
                        lock,
                        org.mockito.Mockito.mock(
                                com.example.omp.payment.grpc.handler.PaymentEventStreamHandler
                                        .class));
        assertThatThrownBy(
                        () ->
                                operations.createOrFindPending(
                                        new PaymentApplicationService.ChargeCommand(
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                Long.MAX_VALUE,
                                                "JPY",
                                                "key"),
                                        "stripe"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, outbox, lock);
    }

    @Test
    void chargingAnExistingGrpcIntentBindsTheProviderWithoutAnotherCreationEvent() {
        var repository = mock(PaymentRepository.class);
        var outbox = mock(OutboxWriter.class);
        UUID order = UUID.randomUUID(), customer = UUID.randomUUID();
        var previous = new Payment(order, customer, Money.usd("10"), "key");
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.of(previous));
        var operations =
                new PaymentTransactionalOps(
                        repository,
                        outbox,
                        mock(PaymentCreationLock.class),
                        org.mockito.Mockito.mock(
                                com.example.omp.payment.grpc.handler.PaymentEventStreamHandler
                                        .class));
        assertThat(
                        operations.createOrFindPending(
                                new PaymentApplicationService.ChargeCommand(
                                        order, customer, 1000, "USD", "key"),
                                "stripe"))
                .isSameAs(previous);
        assertThat(previous.getProvider()).isEqualTo("stripe");
        verifyNoInteractions(outbox);
    }

    @Test
    void keyedPaymentCannotSwitchProvidersOnReplay() {
        var repository = mock(PaymentRepository.class);
        var outbox = mock(OutboxWriter.class);
        UUID order = UUID.randomUUID(), customer = UUID.randomUUID();
        var previous = new Payment(order, customer, Money.usd("10"), "key");
        previous.setProvider("adyen");
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.of(previous));
        var operations =
                new PaymentTransactionalOps(
                        repository,
                        outbox,
                        mock(PaymentCreationLock.class),
                        org.mockito.Mockito.mock(
                                com.example.omp.payment.grpc.handler.PaymentEventStreamHandler
                                        .class));
        assertThatThrownBy(
                        () ->
                                operations.createOrFindPending(
                                        new PaymentApplicationService.ChargeCommand(
                                                order, customer, 1000, "USD", "key"),
                                        "stripe"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(outbox);
    }

    @Test
    void rejectsKeyReuseWithChangedAmountBeforeWritingAnEvent() {
        var repository = mock(PaymentRepository.class);
        var outbox = mock(OutboxWriter.class);
        UUID order = UUID.randomUUID(), customer = UUID.randomUUID();
        var previous = new Payment(order, customer, Money.usd("10"), "key");
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.of(previous));
        var operations =
                new PaymentTransactionalOps(
                        repository,
                        outbox,
                        mock(PaymentCreationLock.class),
                        org.mockito.Mockito.mock(
                                com.example.omp.payment.grpc.handler.PaymentEventStreamHandler
                                        .class));
        assertThatThrownBy(
                        () ->
                                operations.createOrFindPending(
                                        new PaymentApplicationService.ChargeCommand(
                                                order, customer, 2000, "USD", "key"),
                                        "noop"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(outbox);
        verify(repository, never()).save(any());
    }

    @Test
    void duplicateWebhookDoesNotApplyStateOrWriteAnotherEvent() {
        var events = mock(PaymentProviderEventRepository.class);
        var payments = mock(PaymentRepository.class);
        var outbox = mock(OutboxWriter.class);
        var mapper = new ObjectMapper();
        var service =
                new PaymentProviderEventService(
                        events,
                        payments,
                        outbox,
                        mock(com.example.omp.payment.service.RefundStore.class),
                        org.mockito.Mockito.mock(
                                com.example.omp.payment.grpc.handler.PaymentEventStreamHandler
                                        .class));
        var event =
                new ProviderWebhookEvent(
                        "evt-1", "payment_intent.succeeded", mapper.createObjectNode(), "pi-1");
        assertThat(service.process("stripe", event))
                .isEqualTo(PaymentProviderEventService.ProcessResult.DUPLICATE);
        verifyNoInteractions(payments, outbox);
    }
}
