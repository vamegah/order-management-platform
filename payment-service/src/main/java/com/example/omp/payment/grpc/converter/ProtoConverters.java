package com.example.omp.payment.grpc.converter;

import com.example.omp.payment.domain.MoneyConverter;
import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.domain.Refund;

/**
 * Converts domain entities to proto messages.
 *
 * <p>Requires a {@link MoneyConverter} to handle the {@code Money} fields (which appear on every
 * payment and refund). Because of this dependency, the methods here are instance methods, not
 * static — the {@code MoneyConverter} is injected.
 *
 * <p>For a static-only converter (like inventory-service's), see that service's {@code
 * ProtoConverters}. The design difference reflects the domain: inventory has no `Money` fields.
 */
public final class ProtoConverters {

    private ProtoConverters() {
        // Utility class; the MoneyConverter is passed per call.
    }

    /**
     * Converts a {@link Payment} entity to its proto message. Does NOT include the refunds list —
     * use {@link #toProtoWithRefunds} for that.
     */
    public static com.example.omp.proto.payment.Payment toProto(
            Payment payment, MoneyConverter moneyConverter) {
        return base(payment, moneyConverter).build();
    }

    /**
     * Converts a {@link Payment} entity to a proto message that includes its list of refunds. Used
     * by {@code GetPayment} to return the full history.
     */
    public static com.example.omp.proto.payment.Payment toProtoWithRefunds(
            Payment payment, MoneyConverter moneyConverter) {
        var builder = base(payment, moneyConverter);
        for (Refund refund : payment.getRefunds()) {
            builder.addEvents(toPaymentEvent(refund));
        }
        return builder.build();
    }

    /** Converts a {@link Refund} entity to its proto message. */
    public static com.example.omp.proto.payment.Refund toProto(
            Refund refund, MoneyConverter moneyConverter) {
        return com.example.omp.proto.payment.Refund.newBuilder()
                .setId(refund.getId().toString())
                .setPaymentId(refund.getPayment().getId().toString())
                .setAmount(moneyConverter.toProto(refund.getAmount()))
                .setStatus(toProtoStatus(refund.getStatus()))
                .setReason(refund.getReason() != null ? refund.getReason() : "")
                .setCreatedAtEpochMillis(refund.getCreatedAt().toEpochMilli())
                .build();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static com.example.omp.proto.payment.Payment.Builder base(
            Payment payment, MoneyConverter moneyConverter) {
        var builder =
                com.example.omp.proto.payment.Payment.newBuilder()
                        .setId(payment.getId().toString())
                        .setOrderId(payment.getOrderId().toString())
                        .setCustomerId(payment.getCustomerId().toString())
                        .setAmount(moneyConverter.toProto(payment.getAmount()))
                        .setStatus(toProtoStatus(payment.getStatus()))
                        .setCreatedAtEpochMillis(payment.getCreatedAt().toEpochMilli())
                        .setUpdatedAtEpochMillis(payment.getUpdatedAt().toEpochMilli());

        if (payment.getProviderReference() != null) {
            builder.setProviderReference(payment.getProviderReference());
        }
        return builder;
    }

    private static com.example.omp.proto.payment.PaymentEvent toPaymentEvent(Refund refund) {
        // We don't have the from_status for refunds at the payment level;
        // use UNSPECIFIED. Real transitions are recorded in PaymentEvent
        // streams, not in the refund list.
        return com.example.omp.proto.payment.PaymentEvent.newBuilder()
                .setOccurredAtEpochMillis(refund.getCreatedAt().toEpochMilli())
                .setReason("Refund " + refund.getId() + ": " + refund.getReason())
                .build();
    }

    private static com.example.omp.proto.payment.PaymentStatus toProtoStatus(
            com.example.omp.payment.domain.PaymentStatus status) {
        return switch (status) {
            case PENDING -> com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_PENDING;
            case CONFIRMED -> com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_CONFIRMED;
            case CANCELLED -> com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_CANCELLED;
            case FAILED -> com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_FAILED;
            case REFUNDED -> com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_REFUNDED;
            case PARTIALLY_REFUNDED ->
                    com.example.omp.proto.payment.PaymentStatus.PAYMENT_STATUS_PARTIALLY_REFUNDED;
        };
    }

    private static com.example.omp.proto.payment.RefundStatus toProtoStatus(
            com.example.omp.payment.domain.RefundStatus status) {
        return switch (status) {
            case PENDING -> com.example.omp.proto.payment.RefundStatus.REFUND_STATUS_PENDING;
            case COMPLETED -> com.example.omp.proto.payment.RefundStatus.REFUND_STATUS_COMPLETED;
            case FAILED -> com.example.omp.proto.payment.RefundStatus.REFUND_STATUS_FAILED;
        };
    }
}
