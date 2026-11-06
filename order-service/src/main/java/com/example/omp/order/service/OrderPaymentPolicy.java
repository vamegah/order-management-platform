package com.example.omp.order.service;

import com.example.omp.order.clients.grpc.dto.PaymentIntentDto;
import com.example.omp.order.domain.Order;
import com.example.omp.order.error.DownstreamServiceException;
import com.example.omp.order.error.InvalidOrderStateException;
import java.math.BigDecimal;

/** Shared evidence check for confirmation, shipment intent and shipment completion. */
public final class OrderPaymentPolicy {
    private OrderPaymentPolicy() {}

    public static void requireMatchingPayment(Order order, PaymentIntentDto payment) {
        if (payment == null
                || payment.amount() == null
                || !payment.amount().matches("[0-9]{1,15}(\\.[0-9]{1,4})?")) {
            throw new DownstreamServiceException(
                    "payment-service", 502, "Invalid payment response");
        }
        BigDecimal paidAmount = new BigDecimal(payment.amount());
        if (paidAmount.signum() <= 0) {
            throw new DownstreamServiceException(
                    "payment-service", 502, "Invalid payment response");
        }
        if (order.getPaymentId() == null
                || !order.getPaymentId().equals(payment.id())
                || !order.getId().equals(payment.orderId())
                || !order.getCustomerId().equals(payment.customerId())
                || !order.getTotal().getCurrencyCode().equals(payment.currency())
                || order.getTotal().getAmount().compareTo(paidAmount) != 0
                || !"PAYMENT_STATUS_CONFIRMED".equals(payment.status())
                || payment.providerReference() == null
                || payment.providerReference().isBlank()) {
            throw new InvalidOrderStateException(
                    "Order requires a matching, fully confirmed payment");
        }
    }
}
