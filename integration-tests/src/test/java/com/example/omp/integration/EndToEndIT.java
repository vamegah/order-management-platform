package com.example.omp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

import com.example.omp.integration.support.GrpcClientFactory;
import com.example.omp.integration.support.OrderJourney;
import com.example.omp.proto.inventory.ReservationStatus;
import com.example.omp.proto.payment.PaymentStatus;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests covering the full order lifecycle across five services.
 *
 * <p>Requires the full Phase 3 stack to be up:
 *
 * <pre>
 *   docker compose up -d
 *   ./mvnw verify -Pe2e -pl integration-tests
 * </pre>
 *
 * <p>Uses:
 *
 * <ul>
 *   <li>REST Assured for HTTP APIs (order, product, customer).
 *   <li>Generated gRPC stubs for inventory and payment inspection.
 * </ul>
 *
 * <p>Creates its own order; immutable catalog and customer seeds are the only shared fixtures.
 */
@Tag("e2e")
class EndToEndIT {

    @AfterAll
    static void closeChannels() throws InterruptedException {
        GrpcClientFactory.shutdown();
    }

    // =========================================================================
    // Test 1: happy path — create, place, confirm, ship; verify cross-service state
    // =========================================================================

    @Test
    void fullOrderLifecycleWithInventoryAndPayment() {
        var journey = OrderJourney.create();
        assertThat(journey.reservation().getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_ACTIVE);
        assertThat(journey.payment().getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_PENDING);
        journey.transition("ship", true)
                .then()
                .statusCode(409)
                .contentType("application/problem+json");
        journey.transition("place", false).then().statusCode(200).body("status", equalTo("PLACED"));
        journey.transition("confirm", true)
                .then()
                .statusCode(409)
                .contentType("application/problem+json");
        journey.confirmPayment();
        journey.transition("confirm", true)
                .then()
                .statusCode(200)
                .body("status", equalTo("CONFIRMED"));
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                journey.transition("ship", true)
                                        .then()
                                        .statusCode(200)
                                        .body("status", equalTo("SHIPPED")));
        assertThat(journey.reservation().getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_COMMITTED);
        journey.transition("ship", true).then().statusCode(200).body("status", equalTo("SHIPPED"));
        journey.transition("deliver", true)
                .then()
                .statusCode(200)
                .body("status", equalTo("DELIVERED"));
        journey.transition("cancel", false)
                .then()
                .statusCode(409)
                .contentType("application/problem+json");
    }
}
