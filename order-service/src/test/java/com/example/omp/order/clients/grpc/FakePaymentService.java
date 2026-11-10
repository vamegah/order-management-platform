package com.example.omp.order.clients.grpc;

import com.example.omp.proto.payment.*;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process payment fixture for order-service's unit and integration tests.
 *
 * <p>Same design as {@link FakeInventoryService}: in-memory state, configurable behavior, tracks
 * payments by ID for verification.
 */
public class FakePaymentService extends PaymentServiceGrpc.PaymentServiceImplBase {

    private final Map<String, Payment> payments = new ConcurrentHashMap<>();

    @Override
    public void findPaymentsByOrder(
            FindPaymentsByOrderRequest request,
            StreamObserver<FindPaymentsByOrderResponse> observer) {
        observer.onNext(
                FindPaymentsByOrderResponse.newBuilder()
                        .addAllPayments(
                                payments.values().stream()
                                        .filter(p -> p.getOrderId().equals(request.getOrderId()))
                                        .toList())
                        .build());
        observer.onCompleted();
    }

    @Override
    public synchronized void decideFulfillment(
            FulfillmentDecisionRequest request, StreamObserver<FulfillmentDecision> observer) {
        var payment = payments.get(request.getPaymentId());
        if (payment == null || !payment.getOrderId().equals(request.getOrderId())) {
            observer.onError(io.grpc.Status.NOT_FOUND.asRuntimeException());
            return;
        }
        String state =
                payment.getFulfillmentState().isEmpty() ? "OPEN" : payment.getFulfillmentState();
        String next =
                switch (request.getAction()) {
                    case FULFILLMENT_ACTION_BEGIN -> state.equals("OPEN") ? "FULFILLING" : state;
                    case FULFILLMENT_ACTION_SHIP -> state.equals("FULFILLING") ? "SHIPPED" : state;
                    case FULFILLMENT_ACTION_CANCEL ->
                            state.equals("SHIPPED") || state.equals("REFUNDABLE")
                                    ? state
                                    : "CANCELLING";
                    case FULFILLMENT_ACTION_ALLOW_REFUND ->
                            state.equals("CANCELLING") ? "REFUNDABLE" : state;
                    default -> throw new IllegalArgumentException("Invalid fixture decision");
                };
        var updated = payment.toBuilder().setFulfillmentState(next).build();
        payments.put(updated.getId(), updated);
        observer.onNext(
                FulfillmentDecision.newBuilder().setPayment(updated).setState(next).build());
        observer.onCompleted();
    }

    @Override
    public void createPaymentIntent(
            CreatePaymentIntentRequest request, StreamObserver<Payment> observer) {
        // Idempotency: return existing payment for idempotency key.
        if (!request.getIdempotencyKey().isEmpty()) {
            for (Payment p : payments.values()) {
                if (p.getOrderId().equals(request.getOrderId())
                        && p.getStatus() == PaymentStatus.PAYMENT_STATUS_PENDING) {
                    observer.onNext(p);
                    observer.onCompleted();
                    return;
                }
            }
        }

        String id = java.util.UUID.randomUUID().toString();
        Payment payment =
                Payment.newBuilder()
                        .setId(id)
                        .setOrderId(request.getOrderId())
                        .setCustomerId(request.getCustomerId())
                        .setAmount(request.getAmount())
                        .setStatus(PaymentStatus.PAYMENT_STATUS_PENDING)
                        .setCreatedAtEpochMillis(System.currentTimeMillis())
                        .setUpdatedAtEpochMillis(System.currentTimeMillis())
                        .build();

        payments.put(id, payment);

        observer.onNext(payment);
        observer.onCompleted();
    }

    @Override
    public void getPayment(GetPaymentRequest request, StreamObserver<Payment> observer) {
        Payment existing = payments.get(request.getPaymentId());
        if (existing == null) {
            observer.onError(
                    io.grpc.Status.NOT_FOUND
                            .withDescription("Payment not found: " + request.getPaymentId())
                            .asRuntimeException());
            return;
        }
        observer.onNext(existing);
        observer.onCompleted();
    }

    @Override
    public void confirmPayment(ConfirmPaymentRequest request, StreamObserver<Payment> observer) {
        Payment existing = payments.get(request.getPaymentId());
        if (existing == null) {
            observer.onError(
                    io.grpc.Status.NOT_FOUND
                            .withDescription("Payment not found")
                            .asRuntimeException());
            return;
        }
        Payment updated =
                existing.toBuilder()
                        .setStatus(PaymentStatus.PAYMENT_STATUS_CONFIRMED)
                        .setProviderReference(request.getProviderReference())
                        .setUpdatedAtEpochMillis(System.currentTimeMillis())
                        .build();
        payments.put(request.getPaymentId(), updated);
        observer.onNext(updated);
        observer.onCompleted();
    }

    @Override
    public void cancelPayment(CancelPaymentRequest request, StreamObserver<Payment> observer) {
        Payment existing = payments.get(request.getPaymentId());
        if (existing == null) {
            observer.onError(
                    io.grpc.Status.NOT_FOUND
                            .withDescription("Payment not found")
                            .asRuntimeException());
            return;
        }
        Payment updated =
                existing.toBuilder()
                        .setStatus(PaymentStatus.PAYMENT_STATUS_CANCELLED)
                        .setUpdatedAtEpochMillis(System.currentTimeMillis())
                        .build();
        payments.put(request.getPaymentId(), updated);
        observer.onNext(updated);
        observer.onCompleted();
    }
}
