package com.example.omp.integration;

import static io.restassured.RestAssured.given;
import static io.restassured.config.HttpClientConfig.httpClientConfig;
import static io.restassured.config.RestAssuredConfig.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import com.example.omp.integration.support.AuthSupport;
import com.example.omp.integration.support.GrpcClientFactory;
import com.example.omp.integration.support.OrderJourney;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verify administrator admission against the deployed issuer, ingress and database. */
@Tag("e2e")
class OrphanAdmissionIT {
    private static final String ORDER_URL =
            System.getenv().getOrDefault("ORDER_SERVICE_URL", "http://order-service.localhost");
    private static final String RECOVERY_URL = ORDER_URL + "/api/v1/order-recovery/";

    @AfterAll
    static void closeChannels() throws InterruptedException {
        GrpcClientFactory.shutdown();
    }

    @Test
    void onlyAnIssuedAdministratorIdentityCanAdmitAnOrphan() {
        String path = RECOVERY_URL + UUID.randomUUID() + "/adopt";
        var reason = Map.of("reason", "e2e reviewed historical orphan");
        given().config(
                        config().httpClient(
                                        httpClientConfig()
                                                .setParam("http.connection.timeout", 5000)
                                                .setParam("http.socket.timeout", 20000)))
                .contentType("application/json")
                .body(reason)
                .post(path)
                .then()
                .statusCode(401);
        OrderJourney.authenticated(AuthSupport.aliceToken())
                .body(reason)
                .post(path)
                .then()
                .statusCode(403);
        OrderJourney.authenticated(AuthSupport.adminToken())
                .body(reason)
                .post(path)
                .then()
                .statusCode(200)
                .body("submitted", equalTo(true));
    }

    @Test
    void invalidAdmissionRequestsReturnStableProblemsWithoutAdmittingTheIdentity() {
        String path = RECOVERY_URL + UUID.randomUUID() + "/adopt";
        for (String body :
                List.of(
                        "{}",
                        "{\"reason\":null}",
                        "{\"reason\":\" \"}",
                        "{\"reason\":\"" + "x".repeat(501) + "\"}")) {
            OrderJourney.authenticated(AuthSupport.adminToken())
                    .body(body)
                    .post(path)
                    .then()
                    .statusCode(400)
                    .body("status", equalTo(400));
        }
        OrderJourney.authenticated(AuthSupport.adminToken())
                .body(Map.of("reason", "e2e reviewed historical orphan"))
                .post(RECOVERY_URL + "not-a-uuid/adopt")
                .then()
                .statusCode(400)
                .body("status", equalTo(400))
                .body("detail", equalTo("Invalid request parameter"));
        OrderJourney.authenticated(AuthSupport.adminToken())
                .body(Map.of("reason", "e2e corrected admission"))
                .post(path)
                .then()
                .statusCode(200)
                .body("submitted", equalTo(true));
    }

    @Test
    void repeatedAdmissionPreservesTheVerifiedSubjectAndFirstAuditWithoutCreatingAnOrder()
            throws Exception {
        String id = UUID.randomUUID().toString();
        String token = AuthSupport.adminToken();
        // The server verifies the signature; the fixture reads only the expected public subject.
        String subject =
                new ObjectMapper()
                        .readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]))
                        .path("sub")
                        .asText();
        assertThat(subject).isNotBlank();
        var first =
                OrderJourney.authenticated(token)
                        .body(Map.of("reason", "e2e first reviewed orphan"))
                        .post(RECOVERY_URL + id + "/adopt")
                        .then()
                        .statusCode(200)
                        .body("orderId", equalTo(id))
                        .body("status", equalTo("FAILED"))
                        .body("submitted", equalTo(true))
                        .body("requestedBy", equalTo(subject))
                        .body("requestedAt", not(isEmptyOrNullString()))
                        .extract()
                        .response();
        OrderJourney.authenticated(AuthSupport.adminToken())
                .body(Map.of("reason", "e2e replacement reason must not replace the audit"))
                .post(RECOVERY_URL + id + "/adopt")
                .then()
                .statusCode(200)
                .body("orderId", equalTo(id))
                .body("status", equalTo("FAILED"))
                .body("submitted", equalTo(false))
                .body("requestedBy", equalTo(subject))
                .body("reason", equalTo("e2e first reviewed orphan"))
                .body("requestedAt", equalTo(first.<String>path("requestedAt")));
        OrderJourney.authenticated(AuthSupport.adminToken())
                .get(ORDER_URL + "/api/v1/orders/" + id)
                .then()
                .statusCode(404);
    }

    @Test
    void anExistingOrderCannotBeAdmittedOrHaveItsResourcesChanged() {
        var order = OrderJourney.create();
        try {
            var payment = order.payment();
            var reservation = order.reservation();
            OrderJourney.authenticated(AuthSupport.adminToken())
                    .body(Map.of("reason", "e2e existing order must be rejected"))
                    .post(RECOVERY_URL + order.id() + "/adopt")
                    .then()
                    .statusCode(409)
                    .body("detail", equalTo("An existing order cannot be admitted as an orphan"));
            order.read()
                    .then()
                    .body("status", equalTo("DRAFT"))
                    .body("paymentId", equalTo(order.paymentId()))
                    .body("lines[0].reservationId", equalTo(order.reservationId()));
            assertThat(order.payment()).isEqualTo(payment);
            assertThat(order.reservation()).isEqualTo(reservation);
        } finally {
            order.transition("cancel", false)
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("CANCELLED"));
        }
    }
}
