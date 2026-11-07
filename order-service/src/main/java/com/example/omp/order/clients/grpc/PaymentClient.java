package com.example.omp.order.clients.grpc;

import com.example.omp.order.clients.grpc.dto.PaymentIntentDto;
import com.example.omp.order.clients.grpc.dto.RefundDto;
import com.example.omp.order.error.DownstreamServiceException;
import com.example.omp.proto.payment.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.grpc.StatusRuntimeException;
import java.math.BigDecimal;
import java.util.UUID;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gRPC client wrapper for payment-service.
 *
 * <p>Same structure as {@link InventoryClient}: proto types are hidden behind domain DTOs, status
 * exceptions are translated by {@link GrpcExceptionMapper}, calls are wrapped in Resilience4j
 * policies.
 */
@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    private final PaymentServiceGrpc.PaymentServiceBlockingStub stub;
    private final GrpcExceptionMapper exceptionMapper;

    public PaymentClient(
            @GrpcClient("payment-service") PaymentServiceGrpc.PaymentServiceBlockingStub stub,
            GrpcExceptionMapper exceptionMapper) {
        this.stub = stub;
        this.exceptionMapper = exceptionMapper;
    }

    /** Reads payment evidence without mutating provider or payment state. */
    @CircuitBreaker(name = "paymentClient")
    @Retry(name = "paymentClient")
    public PaymentIntentDto get(UUID id) {
        try {
            return toDomain(
                    stub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .getPayment(
                                    GetPaymentRequest.newBuilder()
                                            .setPaymentId(id.toString())
                                            .build()));
        } catch (StatusRuntimeException failure) {
            if (failure.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                throw new com.example.omp.order.error.InvalidOrderStateException(
                        "Order payment was not found");
            }
            // Do not expose downstream descriptions in the public order problem response.
            throw new com.example.omp.order.error.DownstreamServiceException(
                    "payment-service", 502, "Payment verification unavailable");
        } catch (IllegalArgumentException failure) {
            throw new com.example.omp.order.error.DownstreamServiceException(
                    "payment-service", 502, "Invalid payment response");
        }
    }

    /** Creates a payment intent. Idempotent on the provided idempotency key. */
    @CircuitBreaker(name = "paymentClient")
    @Retry(name = "paymentClient")
    public PaymentIntentDto createIntent(
            UUID orderId, UUID customerId, String amount, String currency, String idempotencyKey) {
        try {
            CreatePaymentIntentRequest request =
                    CreatePaymentIntentRequest.newBuilder()
                            .setOrderId(orderId.toString())
                            .setCustomerId(customerId.toString())
                            .setAmount(
                                    com.example.omp.proto.common.Money.newBuilder()
                                            .setAmount(amount)
                                            .setCurrency(currency)
                                            .build())
                            .setIdempotencyKey(idempotencyKey != null ? idempotencyKey : "")
                            .build();

            Payment response =
                    stub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .createPaymentIntent(request);
            PaymentIntentDto payment = verifyIntent(request, response);
            log.debug("Created payment intent for order {}: id={}", orderId, payment.id());
            return payment;
        } catch (StatusRuntimeException e) {
            throw exceptionMapper.map(e, "payment-service");
        }
    }

    /**
     * Confirms a payment (called after the external provider succeeds). Phase 5 will supply the
     * provider reference from a webhook.
     */
    @CircuitBreaker(name = "paymentClient")
    @Retry(name = "paymentClient")
    public PaymentIntentDto confirm(String paymentId, String providerReference) {
        try {
            ConfirmPaymentRequest request =
                    ConfirmPaymentRequest.newBuilder()
                            .setPaymentId(paymentId)
                            .setProviderReference(providerReference)
                            .build();
            return toDomain(
                    stub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .confirmPayment(request));
        } catch (StatusRuntimeException e) {
            throw exceptionMapper.map(e, "payment-service");
        }
    }

    /** Cancels a payment intent. Called when an order is cancelled before confirmation. */
    @CircuitBreaker(name = "paymentClient")
    @Retry(name = "paymentClient")
    public PaymentIntentDto cancel(String paymentId, String reason) {
        try {
            CancelPaymentRequest request =
                    CancelPaymentRequest.newBuilder()
                            .setPaymentId(paymentId)
                            .setReason(reason != null ? reason : "")
                            .build();
            return toDomain(
                    stub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .cancelPayment(request));
        } catch (StatusRuntimeException e) {
            throw exceptionMapper.map(e, "payment-service");
        }
    }

    /** Refunds the remaining balance, with a durable caller key reused after uncertain replies. */
    @CircuitBreaker(name = "paymentClient")
    @Retry(name = "paymentClient")
    public RefundDto refundRemaining(UUID paymentId, String idempotencyKey) {
        try {
            Refund response =
                    stub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .refundPayment(
                                    RefundPaymentRequest.newBuilder()
                                            .setPaymentId(paymentId.toString())
                                            .setIdempotencyKey(idempotencyKey)
                                            .setReason("Order cleanup")
                                            .build());
            return new RefundDto(
                    UUID.fromString(response.getId()),
                    UUID.fromString(response.getPaymentId()),
                    response.getStatus().name());
        } catch (StatusRuntimeException failure) {
            throw new com.example.omp.order.error.DownstreamServiceException(
                    "payment-service", 502, "Refund request unavailable");
        } catch (IllegalArgumentException failure) {
            throw new com.example.omp.order.error.DownstreamServiceException(
                    "payment-service", 502, "Invalid refund response");
        }
    }

    public java.util.List<PaymentIntentDto> findByOrder(UUID orderId) {
        try {
            return stub
                    .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                    .findPaymentsByOrder(
                            FindPaymentsByOrderRequest.newBuilder()
                                    .setOrderId(orderId.toString())
                                    .build())
                    .getPaymentsList()
                    .stream()
                    .map(this::toDomain)
                    .toList();
        } catch (StatusRuntimeException failure) {
            throw exceptionMapper.map(failure, "payment-service");
        }
    }

    private PaymentIntentDto verifyIntent(CreatePaymentIntentRequest request, Payment response) {
        if (!request.getOrderId().equals(response.getOrderId())
                || !request.getCustomerId().equals(response.getCustomerId())
                || !request.getAmount().getCurrency().equals(response.getAmount().getCurrency())
                || !response.getAmount().getAmount().matches("[0-9]{1,15}(\\.[0-9]{1,4})?"))
            throw new DownstreamServiceException(
                    "payment-service", 502, "Payment response does not match checkout");
        boolean pending = response.getStatus() == PaymentStatus.PAYMENT_STATUS_PENDING;
        boolean confirmed =
                response.getStatus() == PaymentStatus.PAYMENT_STATUS_CONFIRMED
                        && !response.getProviderReference().isBlank();
        if (!pending && !confirmed)
            throw new DownstreamServiceException(
                    "payment-service", 502, "Payment response is not usable for checkout");
        try {
            BigDecimal amount = new BigDecimal(response.getAmount().getAmount());
            if (amount.signum() <= 0
                    || amount.compareTo(new BigDecimal(request.getAmount().getAmount())) != 0)
                throw new DownstreamServiceException(
                        "payment-service", 502, "Payment response does not match checkout");
            return toDomain(response);
        } catch (IllegalArgumentException malformedIdentity) {
            throw new DownstreamServiceException(
                    "payment-service", 502, "Payment response has an invalid identity");
        }
    }

    private PaymentIntentDto toDomain(Payment proto) {
        return new PaymentIntentDto(
                UUID.fromString(proto.getId()),
                UUID.fromString(proto.getOrderId()),
                UUID.fromString(proto.getCustomerId()),
                proto.getAmount().getAmount(),
                proto.getAmount().getCurrency(),
                proto.getStatus().name(),
                proto.getProviderReference().isEmpty() ? null : proto.getProviderReference(),
                proto.getCreatedAtEpochMillis(),
                proto.getUpdatedAtEpochMillis());
    }
}
