package com.example.omp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.integration.support.AuthSupport;
import com.example.omp.integration.support.GrpcClientFactory;
import com.example.omp.integration.support.OrderJourney;
import com.example.omp.proto.inventory.GetReservationRequest;
import com.example.omp.proto.inventory.ReservationStatus;
import com.example.omp.proto.payment.Payment;
import com.example.omp.proto.payment.PaymentStatus;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test for the cancellation and refund flow.
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>Cancelling an order transitions its state locally.
 *   <li>Payment intent is cancelled (payment-service state updated).
 *   <li>Refunding a confirmed payment (partial then full) transitions payment state correctly.
 * </ul>
 *
 * <p>Every case creates its own order and verifies persisted references over gRPC.
 */
@Tag("e2e")
class CancellationAndRefundIT {

    private static final String ORDER_URL =
            System.getenv().getOrDefault("ORDER_SERVICE_URL", "http://order-service.localhost");

    @AfterAll
    static void closeChannels() throws InterruptedException {
        GrpcClientFactory.shutdown();
    }

    @Test
    void cancelOrderCancelsPaymentIntent() {
        String runId = UUID.randomUUID().toString().substring(0, 8);

        // Create an order (uses seeded SKU/stock pairs; see EndToEndIT for setup helpers)
        String orderId = createOrderForSeededData(runId);

        // Extract payment ID from the order (order-service exposes it in the response)
        var order = getOrder(orderId);
        String paymentId = order.jsonPath().getString("paymentId");
        String reservationId = order.jsonPath().getString("lines[0].reservationId");
        assertThat(paymentId).isNotBlank();

        // Cancel the order
        OrderJourney.authenticated(AuthSupport.aliceToken())
                .contentType(ContentType.JSON)
                .body("{\"reason\":\"e2e cancellation test\"}")
                .when()
                .post(ORDER_URL + "/api/v1/orders/" + orderId + "/cancel")
                .then()
                .statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("CANCELLED"));

        // Verify payment-service state
        Payment payment =
                GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                        .getPayment(
                                com.example.omp.proto.payment.GetPaymentRequest.newBuilder()
                                        .setPaymentId(paymentId)
                                        .build());

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_CANCELLED);
        var reservation =
                GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                        .getReservation(
                                GetReservationRequest.newBuilder()
                                        .setReservationId(reservationId)
                                        .build());
        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
        OrderJourney.authenticated(AuthSupport.aliceToken())
                .contentType(ContentType.JSON)
                .post(ORDER_URL + "/api/v1/orders/" + orderId + "/cancel")
                .then()
                .statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("CANCELLED"));
        assertThat(getOrder(orderId).jsonPath().<String>getList("statusHistory.toStatus"))
                .containsOnlyOnce("CANCELLED");
    }

    @Test
    void refundConfirmedPaymentProcessesPartialThenFull() {
        // 1. Create, confirm an order
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String orderId = createOrderForSeededData(runId);
        String paymentId = getOrder(orderId).jsonPath().getString("paymentId");

        // 2. Confirm the payment (via gRPC directly, simulating a provider callback)
        Payment confirmed =
                GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                        .confirmPayment(
                                com.example.omp.proto.payment.ConfirmPaymentRequest.newBuilder()
                                        .setPaymentId(paymentId)
                                        .setProviderReference("pi_test_" + runId)
                                        .build());

        assertThat(confirmed.getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_CONFIRMED);

        // 3. Partial refund
        String partialAmount = "5.0000"; // refund $5 of the order
        var partialRefund =
                GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                        .refundPayment(
                                com.example.omp.proto.payment.RefundPaymentRequest.newBuilder()
                                        .setPaymentId(paymentId)
                                        .setAmount(
                                                com.example.omp.proto.common.Money.newBuilder()
                                                        .setAmount(partialAmount)
                                                        .setCurrency("USD")
                                                        .build())
                                        .setReason("partial refund test")
                                        .setIdempotencyKey("refund-" + runId + "-partial")
                                        .build());

        assertThat(partialRefund.getStatus().name()).isEqualTo("REFUND_STATUS_COMPLETED");

        // Verify payment status is PARTIALLY_REFUNDED
        Payment afterPartial =
                GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                        .getPayment(
                                com.example.omp.proto.payment.GetPaymentRequest.newBuilder()
                                        .setPaymentId(paymentId)
                                        .build());
        assertThat(afterPartial.getStatus())
                .isEqualTo(PaymentStatus.PAYMENT_STATUS_PARTIALLY_REFUNDED);

        // 4. Full refund of remaining balance
        String remaining =
                new java.math.BigDecimal(confirmed.getAmount().getAmount())
                        .subtract(new java.math.BigDecimal(partialAmount))
                        .toPlainString();

        GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                .refundPayment(
                        com.example.omp.proto.payment.RefundPaymentRequest.newBuilder()
                                .setPaymentId(paymentId)
                                .setAmount(
                                        com.example.omp.proto.common.Money.newBuilder()
                                                .setAmount(remaining)
                                                .setCurrency("USD")
                                                .build())
                                .setReason("final refund")
                                .setIdempotencyKey("refund-" + runId + "-final")
                                .build());

        Payment afterFull =
                GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                        .getPayment(
                                com.example.omp.proto.payment.GetPaymentRequest.newBuilder()
                                        .setPaymentId(paymentId)
                                        .build());
        assertThat(afterFull.getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_REFUNDED);
        OrderJourney.authenticated(AuthSupport.aliceToken())
                .contentType(ContentType.JSON)
                .post(ORDER_URL + "/api/v1/orders/" + orderId + "/cancel")
                .then()
                .statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("CANCELLED"));
    }

    private String createOrderForSeededData(String runId) {
        var journey = OrderJourney.create();
        journey.transition("place", false).then().statusCode(200);
        return journey.id();
    }

    private Response getOrder(String orderId) {
        return OrderJourney.authenticated(AuthSupport.adminToken())
                .when()
                .get(ORDER_URL + "/api/v1/orders/" + orderId)
                .then()
                .statusCode(200)
                .extract()
                .response();
    }
}
