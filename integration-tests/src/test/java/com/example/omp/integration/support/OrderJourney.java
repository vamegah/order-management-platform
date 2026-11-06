package com.example.omp.integration.support;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.example.omp.proto.inventory.GetReservationRequest;
import com.example.omp.proto.inventory.Reservation;
import com.example.omp.proto.payment.ConfirmPaymentRequest;
import com.example.omp.proto.payment.GetPaymentRequest;
import com.example.omp.proto.payment.Payment;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Independent orders use seeded read-only catalog/customer data, never a shared mutable order. */
public record OrderJourney(String id, String paymentId, String reservationId) {
    public static final String CUSTOMER_ID = "00000000-0000-0000-0000-000000000101";
    private static final String ORDER_URL =
            System.getenv().getOrDefault("ORDER_SERVICE_URL", "http://order-service.localhost");

    public static RequestSpecification authenticated(String token) {
        return given().config(
                        io.restassured.config.RestAssuredConfig.config()
                                .httpClient(
                                        io.restassured.config.HttpClientConfig.httpClientConfig()
                                                .setParam("http.connection.timeout", 5000)
                                                .setParam("http.socket.timeout", 20000)))
                .auth()
                .oauth2(token)
                .contentType("application/json");
    }

    public static OrderJourney create() {
        var response =
                authenticated(AuthSupport.aliceToken())
                        .body(
                                Map.of(
                                        "customerId",
                                        CUSTOMER_ID,
                                        "shippingAddress",
                                        Map.of(
                                                "line1",
                                                "1 Test Way",
                                                "city",
                                                "Oakland",
                                                "postalCode",
                                                "94607",
                                                "country",
                                                "US"),
                                        "lines",
                                        List.of(
                                                Map.of(
                                                        "productId",
                                                        "00000000-0000-0000-0000-000000000204",
                                                        "quantity",
                                                        1)),
                                        "notes",
                                        "e2e-" + UUID.randomUUID()))
                        .post(ORDER_URL + "/api/v1/orders")
                        .then()
                        .statusCode(201)
                        .body("status", equalTo("DRAFT"))
                        .extract()
                        .response();
        var journey =
                new OrderJourney(
                        response.path("id"),
                        response.path("paymentId"),
                        response.path("lines[0].reservationId"));
        assertThat(journey.id()).isNotBlank();
        assertThat(journey.paymentId()).isNotBlank();
        assertThat(journey.reservationId()).isNotBlank();
        var persisted = journey.read();
        assertThat(persisted.<String>path("paymentId")).isEqualTo(journey.paymentId());
        assertThat(persisted.<String>path("lines[0].reservationId"))
                .isEqualTo(journey.reservationId());
        assertThat(journey.payment().getOrderId()).isEqualTo(journey.id());
        assertThat(journey.reservation().getOrderId()).isEqualTo(journey.id());
        return journey;
    }

    public Response read() {
        return authenticated(AuthSupport.aliceToken())
                .get(ORDER_URL + "/api/v1/orders/" + id)
                .then()
                .statusCode(200)
                .extract()
                .response();
    }

    public Response transition(String action, boolean admin) {
        return authenticated(admin ? AuthSupport.adminToken() : AuthSupport.aliceToken())
                .body(Map.of("reason", "e2e " + action))
                .post(ORDER_URL + "/api/v1/orders/" + id + "/" + action);
    }

    public Payment payment() {
        return GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                .getPayment(GetPaymentRequest.newBuilder().setPaymentId(paymentId).build());
    }

    public Reservation reservation() {
        return GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                .getReservation(
                        GetReservationRequest.newBuilder().setReservationId(reservationId).build());
    }

    public void confirmPayment() {
        var request =
                ConfirmPaymentRequest.newBuilder()
                        .setPaymentId(paymentId)
                        .setProviderReference("e2e-" + id)
                        .build();
        var confirmed =
                GrpcClientFactory.paymentClient(AuthSupport.adminToken()).confirmPayment(request);
        var replay =
                GrpcClientFactory.paymentClient(AuthSupport.adminToken()).confirmPayment(request);
        assertThat(replay.getId()).isEqualTo(confirmed.getId());
        assertThat(replay.getStatus()).isEqualTo(confirmed.getStatus());
        assertThat(replay.getProviderReference()).isEqualTo(confirmed.getProviderReference());
    }
}
