package com.example.omp.payment.grpc;

import com.example.omp.payment.grpc.handler.PaymentEventStreamHandler;
import com.example.omp.payment.grpc.handler.PaymentIntentHandler;
import com.example.omp.payment.grpc.handler.PaymentQueryHandler;
import com.example.omp.payment.grpc.handler.RefundHandler;
import com.example.omp.proto.payment.CancelPaymentRequest;
import com.example.omp.proto.payment.ConfirmPaymentRequest;
import com.example.omp.proto.payment.CreatePaymentIntentRequest;
import com.example.omp.proto.payment.GetPaymentRequest;
import com.example.omp.proto.payment.Payment;
import com.example.omp.proto.payment.PaymentEvent;
import com.example.omp.proto.payment.PaymentServiceGrpc;
import com.example.omp.proto.payment.Refund;
import com.example.omp.proto.payment.RefundPaymentRequest;
import com.example.omp.proto.payment.StreamPaymentEventsRequest;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC implementation of {@link PaymentServiceGrpc.PaymentServiceImplBase}.
 *
 * <p>Same pattern as inventory-service's {@code InventoryServiceImpl}: each RPC delegates to a
 * specialized handler, and this class is a thin translation layer.
 *
 * <p><b>Patterns used:</b>
 *
 * <ul>
 *   <li><b>Unary</b> — all methods except {@code StreamPaymentEvents}.
 *   <li><b>Server streaming</b> — {@code StreamPaymentEvents}.
 * </ul>
 *
 * No client streaming or bidirectional streaming. Those aren't needed for the payment domain's
 * access patterns.
 *
 * <p><b>Error handling:</b> handlers throw domain exceptions; the {@code GrpcExceptionAdvice} maps
 * them to gRPC status codes.
 */
@GrpcService
public class PaymentServiceImpl extends PaymentServiceGrpc.PaymentServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    @Override
    public void decideFulfillment(
            com.example.omp.proto.payment.FulfillmentDecisionRequest request,
            StreamObserver<com.example.omp.proto.payment.FulfillmentDecision> observer) {
        try {
            observer.onNext(intentHandler.decideFulfillment(request));
            observer.onCompleted();
        } catch (Exception failure) {
            observer.onError(
                    com.example.omp.payment.grpc.advice.GrpcExceptionAdvice.toStatus(failure)
                            .asRuntimeException());
        }
    }

    private final PaymentIntentHandler intentHandler;
    private final PaymentQueryHandler queryHandler;
    private final RefundHandler refundHandler;
    private final PaymentEventStreamHandler streamHandler;

    public PaymentServiceImpl(
            PaymentIntentHandler intentHandler,
            PaymentQueryHandler queryHandler,
            RefundHandler refundHandler,
            PaymentEventStreamHandler streamHandler) {
        this.intentHandler = intentHandler;
        this.queryHandler = queryHandler;
        this.refundHandler = refundHandler;
        this.streamHandler = streamHandler;
    }

    @Override
    public void findPaymentsByOrder(
            com.example.omp.proto.payment.FindPaymentsByOrderRequest request,
            StreamObserver<com.example.omp.proto.payment.FindPaymentsByOrderResponse> observer) {
        try {
            observer.onNext(queryHandler.findByOrder(request));
            observer.onCompleted();
        } catch (org.springframework.security.access.AccessDeniedException failure) {
            observer.onError(
                    io.grpc.Status.PERMISSION_DENIED
                            .withDescription("Recovery scope required")
                            .asRuntimeException());
        } catch (org.springframework.security.core.AuthenticationException failure) {
            observer.onError(
                    io.grpc.Status.UNAUTHENTICATED
                            .withDescription("Authentication required")
                            .asRuntimeException());
        } catch (IllegalArgumentException failure) {
            observer.onError(
                    io.grpc.Status.INVALID_ARGUMENT
                            .withDescription("Invalid order ID")
                            .asRuntimeException());
        } catch (IllegalStateException failure) {
            observer.onError(
                    io.grpc.Status.FAILED_PRECONDITION
                            .withDescription("Recovery lookup limit exceeded")
                            .asRuntimeException());
        } catch (Exception failure) {
            observer.onError(
                    io.grpc.Status.INTERNAL
                            .withDescription("Recovery lookup failed")
                            .asRuntimeException());
        }
    }

    // =========================================================================
    // Payment intents (unary)
    // =========================================================================

    @Override
    public void createPaymentIntent(
            CreatePaymentIntentRequest request, StreamObserver<Payment> responseObserver) {
        log.debug(
                "CreatePaymentIntent order={} customer={}",
                request.getOrderId(),
                request.getCustomerId());
        try {
            Payment response = intentHandler.create(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                    com.example.omp.payment.grpc.advice.GrpcExceptionAdvice.toStatus(e)
                            .asRuntimeException());
        }
    }

    @Override
    public void confirmPayment(
            ConfirmPaymentRequest request, StreamObserver<Payment> responseObserver) {
        log.debug(
                "ConfirmPayment id={} providerRef={}",
                request.getPaymentId(),
                request.getProviderReference());
        try {
            Payment response = intentHandler.confirm(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                    com.example.omp.payment.grpc.advice.GrpcExceptionAdvice.toStatus(e)
                            .asRuntimeException());
        }
    }

    @Override
    public void cancelPayment(
            CancelPaymentRequest request, StreamObserver<Payment> responseObserver) {
        log.debug("CancelPayment id={}", request.getPaymentId());
        try {
            Payment response = intentHandler.cancel(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                    com.example.omp.payment.grpc.advice.GrpcExceptionAdvice.toStatus(e)
                            .asRuntimeException());
        }
    }

    // =========================================================================
    // Queries (unary)
    // =========================================================================

    @Override
    public void getPayment(GetPaymentRequest request, StreamObserver<Payment> responseObserver) {
        log.debug("GetPayment id={}", request.getPaymentId());
        try {
            Payment response = queryHandler.get(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                    com.example.omp.payment.grpc.advice.GrpcExceptionAdvice.toStatus(e)
                            .asRuntimeException());
        }
    }

    // =========================================================================
    // Refunds (unary)
    // =========================================================================

    @Override
    public void refundPayment(
            RefundPaymentRequest request, StreamObserver<Refund> responseObserver) {
        log.debug("RefundPayment paymentId={}", request.getPaymentId());
        try {
            Refund response = refundHandler.refund(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(
                    com.example.omp.payment.grpc.advice.GrpcExceptionAdvice.toStatus(e)
                            .asRuntimeException());
        }
    }

    // =========================================================================
    // Server streaming — payment events
    // =========================================================================
    //
    // Same pattern as inventory-service's StreamStockUpdates. The client
    // subscribes with a filter (order_id or customer_id); the server pushes
    // a PaymentEvent on every state change that matches the filter.
    //
    // The handler manages the ServerCallStreamObserver's lifecycle —
    // registering cancellation, respecting backpressure.
    // =========================================================================

    @Override
    public void streamPaymentEvents(
            StreamPaymentEventsRequest request, StreamObserver<PaymentEvent> responseObserver) {
        log.debug(
                "StreamPaymentEvents filter orderId={} customerId={}",
                request.getOrderId(),
                request.getCustomerId());
        streamHandler.streamEvents(request, responseObserver);
    }
}
