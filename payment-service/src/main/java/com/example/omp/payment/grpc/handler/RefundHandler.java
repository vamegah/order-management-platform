package com.example.omp.payment.grpc.handler;

import com.example.omp.payment.domain.MoneyConverter;
import com.example.omp.payment.grpc.converter.ProtoConverters;
import com.example.omp.payment.service.RefundApplicationService;
import com.example.omp.proto.payment.RefundPaymentRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** gRPC adapter for the same provider-backed refund workflow exposed over REST. */
@Component
public class RefundHandler {
    private final RefundApplicationService refunds;
    private final MoneyConverter money;

    public RefundHandler(RefundApplicationService refunds, MoneyConverter money) {
        this.refunds = refunds;
        this.money = money;
    }

    public com.example.omp.proto.payment.Refund refund(RefundPaymentRequest request) {
        UUID paymentId = UUID.fromString(request.getPaymentId());
        return ProtoConverters.toProto(
                refunds.refund(
                        paymentId,
                        request.hasAmount() ? money.toDomain(request.getAmount()) : null,
                        request.getReason().isEmpty() ? null : request.getReason(),
                        request.getIdempotencyKey()),
                money);
    }
}
