package com.example.omp.payment.grpc.handler;

import com.example.omp.payment.domain.Payment;
import com.example.omp.proto.payment.PaymentEvent;
import com.example.omp.proto.payment.PaymentStatus;
import com.example.omp.proto.payment.StreamPaymentEventsRequest;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles the server-streaming {@code StreamPaymentEvents} RPC.
 *
 * <p>Same pattern as inventory-service's {@code StockUpdateStreamHandler}: in-memory subscription
 * registry, fan-out on state change, backpressure via {@link ServerCallStreamObserver#isReady()},
 * cancellation via {@code setOnCancelHandler}.
 *
 * <p><b>Filters:</b> subscribers can filter by {@code order_id} or {@code customer_id}. A
 * subscription matches an event if any of its non-empty filters matches.
 *
 * <p><b>Known limitation (Phase 3):</b> subscriptions are per-replica. Multiple inventory-service
 * replicas don't share the registry. Phase 7 adds Redis pub/sub for cross-replica fan-out.
 */
@Component
public class PaymentEventStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventStreamHandler.class);

    private final ConcurrentMap<Long, Subscription> subscribers = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    public void streamEvents(
            StreamPaymentEventsRequest request, StreamObserver<PaymentEvent> responseObserver) {

        if (!(responseObserver instanceof ServerCallStreamObserver<PaymentEvent> serverObserver)) {
            throw new IllegalStateException(
                    "Expected ServerCallStreamObserver, got " + responseObserver.getClass());
        }

        long id = idGen.incrementAndGet();
        Subscription sub =
                new Subscription(
                        id,
                        emptyToNull(request.getOrderId()),
                        emptyToNull(request.getCustomerId()),
                        serverObserver);

        subscribers.put(id, sub);
        log.info(
                "Payment event subscription opened: id={} orderId={} customerId={}",
                id,
                sub.orderId,
                sub.customerId);

        serverObserver.setOnCancelHandler(
                () -> {
                    subscribers.remove(id);
                    log.info("Payment event subscription closed: id={}", id);
                });

        // The stream is kept open. No onCompleted call here — the client
        // controls when to close.
    }

    /**
     * Publishes a state-change event to all matching subscribers. Called by {@code
     * PaymentIntentHandler} and {@code RefundHandler} after each transition.
     */
    public void publishEvent(Payment payment, PaymentStatus fromStatus, String reason) {
        PaymentEvent event =
                PaymentEvent.newBuilder()
                        .setPaymentId(payment.getId().toString())
                        .setOrderId(payment.getOrderId().toString())
                        .setFromStatus(fromStatus)
                        .setToStatus(toProtoStatus(payment.getStatus()))
                        .setReason(reason != null ? reason : "")
                        .setOccurredAtEpochMillis(Instant.now().toEpochMilli())
                        .build();

        int delivered = 0;
        for (Subscription sub : subscribers.values()) {
            if (sub.matches(payment)) {
                try {
                    if (sub.observer.isReady()) {
                        sub.observer.onNext(event);
                        delivered++;
                    }
                } catch (Exception e) {
                    log.warn(
                            "Failed to deliver event to subscription {}: {}",
                            sub.id,
                            e.getMessage());
                    subscribers.remove(sub.id);
                }
            }
        }

        if (delivered > 0) {
            log.debug(
                    "Published payment event {} -> {} to {} subscribers",
                    fromStatus,
                    payment.getStatus(),
                    delivered);
        }
    }

    private PaymentStatus toProtoStatus(com.example.omp.payment.domain.PaymentStatus status) {
        return switch (status) {
            case PENDING -> PaymentStatus.PAYMENT_STATUS_PENDING;
            case CONFIRMED -> PaymentStatus.PAYMENT_STATUS_CONFIRMED;
            case CANCELLED -> PaymentStatus.PAYMENT_STATUS_CANCELLED;
            case FAILED -> PaymentStatus.PAYMENT_STATUS_FAILED;
            case REFUNDED -> PaymentStatus.PAYMENT_STATUS_REFUNDED;
            case PARTIALLY_REFUNDED -> PaymentStatus.PAYMENT_STATUS_PARTIALLY_REFUNDED;
        };
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static final class Subscription {
        final long id;
        final String orderId;
        final String customerId;
        final ServerCallStreamObserver<PaymentEvent> observer;

        Subscription(
                long id,
                String orderId,
                String customerId,
                ServerCallStreamObserver<PaymentEvent> observer) {
            this.id = id;
            this.orderId = orderId;
            this.customerId = customerId;
            this.observer = observer;
        }

        boolean matches(Payment payment) {
            // A subscription with no filters matches everything (subject to auth).
            if (orderId == null && customerId == null) {
                return true;
            }
            if (orderId != null && orderId.equals(payment.getOrderId().toString())) {
                return true;
            }
            return customerId != null && customerId.equals(payment.getCustomerId().toString());
        }
    }
}
