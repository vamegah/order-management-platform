package com.example.omp.payment.grpc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.payment.domain.MoneyConverter;
import com.example.omp.payment.events.PaymentEventPublisher;
import com.example.omp.payment.grpc.handler.*;
import com.example.omp.payment.repository.PaymentRepository;
import com.example.omp.payment.service.PaymentCreationLock;
import com.example.omp.proto.payment.*;
import com.example.omp.shared.domain.Money;
import io.grpc.*;
import io.grpc.inprocess.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentIntentContractTest {
    private Server server;
    private ManagedChannel channel;
    private PaymentServiceGrpc.PaymentServiceBlockingStub client;
    private PaymentRepository repository;
    private PaymentEventPublisher events;
    private PaymentCreationLock lock;
    private com.example.omp.payment.domain.Payment existing;
    private final UUID orderId = UUID.randomUUID(), customerId = UUID.randomUUID();

    @BeforeEach
    void start() throws Exception {
        repository = mock(PaymentRepository.class);
        events = mock(PaymentEventPublisher.class);
        lock = mock(PaymentCreationLock.class);
        existing =
                new com.example.omp.payment.domain.Payment(
                        orderId, customerId, Money.usd("10"), "key");
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(existing, "createdAt", Instant.now());
        ReflectionTestUtils.setField(existing, "updatedAt", Instant.now());
        when(repository.findByIdempotencyKey("key")).thenReturn(Optional.of(existing));
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        var handler = new PaymentIntentHandler(repository, new MoneyConverter(), events, lock);
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                new PaymentServiceImpl(
                                        handler,
                                        mock(PaymentQueryHandler.class),
                                        mock(RefundHandler.class),
                                        mock(PaymentEventStreamHandler.class)))
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = PaymentServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private FulfillmentDecision decide(FulfillmentAction action) {
        return client.decideFulfillment(
                FulfillmentDecisionRequest.newBuilder()
                        .setPaymentId(existing.getId().toString())
                        .setOrderId(orderId.toString())
                        .setAction(action)
                        .build());
    }

    @Test
    void durableShipmentDecisionAndCancellationReplayShareTheWireContract() {
        when(repository.lockForRefund(existing.getId())).thenReturn(Optional.of(existing));
        existing.confirm("pi-confirmed");
        assertThat(decide(FulfillmentAction.FULFILLMENT_ACTION_BEGIN).getState())
                .isEqualTo("FULFILLING");
        assertThat(
                        decide(FulfillmentAction.FULFILLMENT_ACTION_SHIP)
                                .getPayment()
                                .getFulfillmentState())
                .isEqualTo("SHIPPED");
        assertThat(decide(FulfillmentAction.FULFILLMENT_ACTION_CANCEL).getState())
                .isEqualTo("SHIPPED");
        assertThatThrownBy(() -> decide(FulfillmentAction.FULFILLMENT_ACTION_ALLOW_REFUND))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e ->
                                assertThat(e.getStatus().getCode())
                                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
    }

    @Test
    void cancellationDecisionPreventsShipmentAndAllowsRefundOnlyAfterCompensation() {
        when(repository.lockForRefund(existing.getId())).thenReturn(Optional.of(existing));
        existing.confirm("pi-confirmed");
        assertThat(decide(FulfillmentAction.FULFILLMENT_ACTION_CANCEL).getState())
                .isEqualTo("CANCELLING");
        assertThatThrownBy(() -> decide(FulfillmentAction.FULFILLMENT_ACTION_BEGIN))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e ->
                                assertThat(e.getStatus().getCode())
                                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
        assertThat(decide(FulfillmentAction.FULFILLMENT_ACTION_ALLOW_REFUND).getState())
                .isEqualTo("REFUNDABLE");
        assertThat(decide(FulfillmentAction.FULFILLMENT_ACTION_CANCEL).getState())
                .isEqualTo("REFUNDABLE");
    }

    @Test
    void invalidDecisionActionsAndForeignOrdersCannotChangePayment() {
        when(repository.lockForRefund(existing.getId())).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> decide(FulfillmentAction.FULFILLMENT_ACTION_UNSPECIFIED))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e ->
                                assertThat(e.getStatus().getCode())
                                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
        assertThatThrownBy(
                        () ->
                                client.decideFulfillment(
                                        FulfillmentDecisionRequest.newBuilder()
                                                .setPaymentId(existing.getId().toString())
                                                .setOrderId(UUID.randomUUID().toString())
                                                .setAction(
                                                        FulfillmentAction.FULFILLMENT_ACTION_CANCEL)
                                                .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e ->
                                assertThat(e.getStatus().getCode())
                                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
        assertThat(existing.getFulfillmentState()).isEqualTo("OPEN");
    }

    private CreatePaymentIntentRequest.Builder request() {
        return CreatePaymentIntentRequest.newBuilder()
                .setOrderId(orderId.toString())
                .setCustomerId(customerId.toString())
                .setAmount(
                        com.example.omp.proto.common.Money.newBuilder()
                                .setAmount("10.00")
                                .setCurrency("USD"))
                .setIdempotencyKey("key");
    }

    private void code(Runnable operation, Status.Code expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        failure -> assertThat(failure.getStatus().getCode()).isEqualTo(expected));
    }

    @Test
    void identicalReplayReturnsSameIntentWithoutAnotherEvent() {
        assertThat(client.createPaymentIntent(request().build()).getId())
                .isEqualTo(existing.getId().toString());
        var order = inOrder(lock, repository);
        order.verify(lock).acquire("key");
        order.verify(repository).findByIdempotencyKey("key");
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "customer", "amount", "currency"})
    void changedReplayIsAConflict(String changed) {
        var request = request();
        switch (changed) {
            case "order" -> request.setOrderId(UUID.randomUUID().toString());
            case "customer" -> request.setCustomerId(UUID.randomUUID().toString());
            case "amount" ->
                    request.setAmount(
                            com.example.omp.proto.common.Money.newBuilder()
                                    .setAmount("11")
                                    .setCurrency("USD"));
            case "currency" ->
                    request.setAmount(
                            com.example.omp.proto.common.Money.newBuilder()
                                    .setAmount("10")
                                    .setCurrency("EUR"));
        }
        code(() -> client.createPaymentIntent(request.build()), Status.Code.ALREADY_EXISTS);
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void fractionalMinorUnitsAreRejectedBeforeLockOrLookup() {
        var request =
                request()
                        .setAmount(
                                com.example.omp.proto.common.Money.newBuilder()
                                        .setAmount("10.001")
                                        .setCurrency("USD"))
                        .build();
        code(() -> client.createPaymentIntent(request), Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(lock, repository, events);
    }

    @Test
    void lockContentionIsRetryableAndDoesNotExposeSqlDetails() {
        doThrow(new org.springframework.dao.CannotAcquireLockException("secret SQL details"))
                .when(lock)
                .acquire("key");
        assertThatThrownBy(() -> client.createPaymentIntent(request().build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        failure -> {
                            assertThat(failure.getStatus().getCode())
                                    .isEqualTo(Status.Code.ABORTED);
                            assertThat(failure.getStatus().getDescription())
                                    .doesNotContain("secret");
                        });
        verifyNoInteractions(repository, events);
    }

    @Test
    void confirmedPaymentReplayRequiresSameProviderReference() {
        existing.confirm("pi-1");
        var request =
                ConfirmPaymentRequest.newBuilder()
                        .setPaymentId(existing.getId().toString())
                        .setProviderReference("pi-1");
        assertThat(client.confirmPayment(request.build()).getStatus())
                .isEqualTo(PaymentStatus.PAYMENT_STATUS_CONFIRMED);
        code(
                () -> client.confirmPayment(request.setProviderReference("pi-2").build()),
                Status.Code.FAILED_PRECONDITION);
        verifyNoInteractions(events);
    }

    @Test
    void cancellationIsIdempotentAndBlocksLaterConfirmation() {
        var request =
                CancelPaymentRequest.newBuilder()
                        .setPaymentId(existing.getId().toString())
                        .setReason("Cancelled")
                        .build();
        client.cancelPayment(request);
        client.cancelPayment(request);
        code(
                () ->
                        client.confirmPayment(
                                ConfirmPaymentRequest.newBuilder()
                                        .setPaymentId(existing.getId().toString())
                                        .setProviderReference("pi-1")
                                        .build()),
                Status.Code.FAILED_PRECONDITION);
        verify(events).publishCancelled(existing, "Cancelled");
        verifyNoMoreInteractions(events);
    }
}
