package com.example.omp.payment.grpc.handler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.domain.PaymentStatus;
import com.example.omp.proto.payment.PaymentEvent;
import com.example.omp.proto.payment.StreamPaymentEventsRequest;
import com.example.omp.shared.domain.Money;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentEventStreamHandlerTest {
    private final PaymentEventStreamHandler streams = new PaymentEventStreamHandler();

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void deliversEveryLifecycleStateWithoutCompletingTheSubscription(PaymentStatus status) {
        var payment = payment();
        switch (status) {
            case CONFIRMED -> payment.confirm("pi");
            case CANCELLED -> payment.cancel("cancelled");
            case FAILED -> payment.markFailed("declined");
            case PARTIALLY_REFUNDED -> {
                payment.confirm("pi");
                payment.applyRefund(Money.usd("1"));
            }
            case REFUNDED -> {
                payment.confirm("pi");
                payment.applyRefund(Money.usd("10"));
            }
            case PENDING -> {}
        }
        var observer = observer();
        streams.streamEvents(StreamPaymentEventsRequest.getDefaultInstance(), observer);
        streams.publishEvent(
                payment, com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_PENDING, null);
        var event = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(observer).onNext(event.capture());
        assertThat(event.getValue().getPaymentId()).isEqualTo(payment.getId().toString());
        assertThat(event.getValue().getOrderId()).isEqualTo(payment.getOrderId().toString());
        assertThat(event.getValue().getToStatus().name()).isEqualTo("PAYMENT_STATUS_" + status);
        assertThat(event.getValue().getReason()).isEmpty();
        assertThat(event.getValue().getOccurredAtEpochMillis()).isPositive();
        verify(observer, never()).onCompleted();
    }

    @Test
    void orderAndCustomerFiltersMatchIndependentlyAndForeignEventsAreExcluded() {
        var payment = payment();
        var byOrder = observer();
        var byCustomer = observer();
        var foreign = observer();
        streams.streamEvents(
                StreamPaymentEventsRequest.newBuilder()
                        .setOrderId(payment.getOrderId().toString())
                        .setCustomerId(UUID.randomUUID().toString())
                        .build(),
                byOrder);
        streams.streamEvents(
                StreamPaymentEventsRequest.newBuilder()
                        .setOrderId(UUID.randomUUID().toString())
                        .setCustomerId(payment.getCustomerId().toString())
                        .build(),
                byCustomer);
        streams.streamEvents(
                StreamPaymentEventsRequest.newBuilder()
                        .setOrderId(UUID.randomUUID().toString())
                        .setCustomerId(UUID.randomUUID().toString())
                        .build(),
                foreign);
        streams.publishEvent(
                payment,
                com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_PENDING,
                "changed");
        verify(byOrder).onNext(any());
        verify(byCustomer).onNext(any());
        verify(foreign, never()).onNext(any());
    }

    @Test
    void readinessCancellationAndObserverFailureBoundDelivery() {
        var payment = payment();
        var slow = observer();
        when(slow.isReady()).thenReturn(false);
        streams.streamEvents(StreamPaymentEventsRequest.getDefaultInstance(), slow);
        var cancellation = ArgumentCaptor.forClass(Runnable.class);
        verify(slow).setOnCancelHandler(cancellation.capture());
        streams.publishEvent(
                payment,
                com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_PENDING,
                "slow");
        verify(slow, never()).onNext(any());
        when(slow.isReady()).thenReturn(true);
        streams.publishEvent(
                payment,
                com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_PENDING,
                "ready");
        cancellation.getValue().run();
        streams.publishEvent(
                payment,
                com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_PENDING,
                "cancelled");
        verify(slow, times(1)).onNext(any());
        var broken = observer();
        doThrow(new IllegalStateException("disconnected")).when(broken).onNext(any());
        streams.streamEvents(StreamPaymentEventsRequest.getDefaultInstance(), broken);
        streams.publishEvent(
                payment,
                com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_PENDING,
                "first");
        streams.publishEvent(
                payment,
                com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_PENDING,
                "second");
        verify(broken, times(1)).onNext(any());
    }

    private Payment payment() {
        var payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), Money.usd("10"));
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        return payment;
    }

    @SuppressWarnings("unchecked")
    private ServerCallStreamObserver<PaymentEvent> observer() {
        var observer =
                (ServerCallStreamObserver<PaymentEvent>) mock(ServerCallStreamObserver.class);
        when(observer.isReady()).thenReturn(true);
        return observer;
    }
}
