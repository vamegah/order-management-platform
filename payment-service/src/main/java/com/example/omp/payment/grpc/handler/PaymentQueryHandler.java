package com.example.omp.payment.grpc.handler;

import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.error.PaymentNotFoundException;
import com.example.omp.payment.grpc.converter.ProtoConverters;
import com.example.omp.payment.repository.PaymentRepository;
import com.example.omp.proto.payment.GetPaymentRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Handles read operations for payments. */
@Component
public class PaymentQueryHandler {

    private final PaymentRepository paymentRepository;

    public PaymentQueryHandler(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasAuthority('SCOPE_order.reconcile')")
    public com.example.omp.proto.payment.FindPaymentsByOrderResponse findByOrder(
            com.example.omp.proto.payment.FindPaymentsByOrderRequest request) {
        var payments =
                paymentRepository.findTop101ByOrderIdOrderByCreatedAtAsc(
                        UUID.fromString(request.getOrderId()));
        if (payments.size() > 100)
            throw new IllegalStateException("Recovery lookup exceeds 100 payments");
        return com.example.omp.proto.payment.FindPaymentsByOrderResponse.newBuilder()
                .addAllPayments(
                        payments.stream()
                                .map(
                                        payment ->
                                                ProtoConverters.toProto(
                                                        payment,
                                                        new com.example.omp.payment.domain
                                                                .MoneyConverter()))
                                .toList())
                .build();
    }

    @Transactional(readOnly = true)
    public com.example.omp.proto.payment.Payment get(GetPaymentRequest request) {
        UUID id;
        try {
            id = UUID.fromString(request.getPaymentId());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid payment ID: " + request.getPaymentId(), e);
        }

        // Eagerly load refunds so the response includes the full history.
        Payment payment =
                paymentRepository
                        .findWithRefundsById(id)
                        .orElseThrow(
                                () -> new PaymentNotFoundException("Payment not found: " + id));

        return ProtoConverters.toProtoWithRefunds(
                payment, new com.example.omp.payment.domain.MoneyConverter());
    }
}
