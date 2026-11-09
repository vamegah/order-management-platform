package com.example.omp.order.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.example.omp.order.AbstractIntegrationTest;
import com.example.omp.order.TestSecurityConfig;
import com.example.omp.order.WireMockDownstreamConfig;
import com.example.omp.order.clients.grpc.InventoryClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real HTTP/PostgreSQL rejection and repair; downstream resources use in-process RPC fixtures. */
class OrderReferenceIntegrityIT extends AbstractIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryClient inventory;

    @ParameterizedTest
    @CsvSource({
        "place,MISSING", "place,SHARED", "confirm,MISSING", "confirm,SHARED",
        "ship,MISSING", "ship,SHARED", "deliver,MISSING", "deliver,SHARED"
    })
    void invalidPersistedReferencesCannotAdvanceOrChangeDurableAndRemoteState(
            String operation, String fault) {
        var fixture = createTwoLineDraft();
        payments.confirm(fixture.payment().toString(), "pi-reference-integrity");
        if (!operation.equals("place")) command(fixture.order(), "place", 200);
        if (operation.equals("ship") || operation.equals("deliver"))
            command(fixture.order(), "confirm", 200);
        if (operation.equals("deliver")) command(fixture.order(), "ship", 200);
        var holds =
                fixture.lines().stream().map(line -> inventory.get(line.reservation())).toList();
        var payment = payments.get(fixture.payment());

        // Emulate legacy/corrupt stored metadata after a valid checkout and any preceding steps.
        if (fault.equals("MISSING")) {
            jdbc.update(
                    "UPDATE orders.order_line SET reservation_id = NULL WHERE id = ?",
                    fixture.lines().getFirst().line());
        } else {
            jdbc.update(
                    "UPDATE orders.order_line SET reservation_id = ? WHERE order_id = ?",
                    fixture.lines().getFirst().reservation(),
                    fixture.order());
        }
        var before = snapshot(fixture.order());

        for (int retry = 0; retry < 2; retry++) command(fixture.order(), operation, 409);

        assertThat(snapshot(fixture.order())).isEqualTo(before);
        assertThat(fixture.lines().stream().map(line -> inventory.get(line.reservation())).toList())
                .containsExactlyElementsOf(holds);
        assertThat(payments.get(fixture.payment())).isEqualTo(payment);
    }

    @Test
    void verifiedCompleteRepairAllowsPlacementAndConfirmationAfterSafeRejection() {
        var fixture = createTwoLineDraft();
        jdbc.update(
                "UPDATE orders.order_line SET reservation_id = NULL WHERE order_id = ?",
                fixture.order());
        var before = snapshot(fixture.order());
        command(fixture.order(), "place", 409);
        assertThat(snapshot(fixture.order())).isEqualTo(before);
        long version =
                jdbc.queryForObject(
                        "SELECT version FROM orders.purchase_order WHERE id = ?",
                        Long.class,
                        fixture.order());
        var plan =
                Map.of(
                        "expectedVersion",
                        version,
                        "reason",
                        "Verified existing order-owned inventory holds",
                        "bindings",
                        fixture.lines().stream()
                                .map(
                                        line ->
                                                Map.of(
                                                        "lineId",
                                                        line.line(),
                                                        "reservationId",
                                                        line.reservation()))
                                .toList());
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType("application/json")
                .body(plan)
                .post("/api/v1/order-reservation-repairs/" + fixture.order())
                .then()
                .statusCode(200)
                .body("restored", equalTo(2));
        var repaired = snapshot(fixture.order());
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType("application/json")
                .body(plan)
                .post("/api/v1/order-reservation-repairs/" + fixture.order())
                .then()
                .statusCode(200)
                .body("restored", equalTo(0));
        assertThat(snapshot(fixture.order())).isEqualTo(repaired);
        assertThat(
                        jdbc.queryForList(
                                "SELECT actor FROM orders.reservation_reference_repair WHERE order_id = ? ORDER BY id",
                                String.class,
                                fixture.order()))
                .containsExactly("test-admin", "test-admin");

        command(fixture.order(), "place", 200);
        payments.confirm(fixture.payment().toString(), "pi-repaired-reference-order");
        command(fixture.order(), "confirm", 200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM orders.purchase_order WHERE id = ?",
                                String.class,
                                fixture.order()))
                .isEqualTo("CONFIRMED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT version FROM orders.purchase_order WHERE id = ?",
                                Long.class,
                                fixture.order()))
                .isEqualTo(version + 3);
        assertThat(
                        fixture.lines().stream()
                                .map(line -> inventory.get(line.reservation()).status())
                                .toList())
                .containsExactly("RESERVATION_STATUS_ACTIVE", "RESERVATION_STATUS_ACTIVE");
    }

    @Test
    void historicalDraftCanBeCancelledWithoutFabricatingReferencesOrAnAudit() {
        UUID historical = UUID.fromString("00000000-0000-0000-0000-000000000303");
        command(historical, "place", 409);
        command(historical, "cancel", 200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.order_line WHERE order_id = ? AND reservation_id IS NULL",
                                Long.class,
                                historical))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.terminal_reservation_reference_disposition WHERE order_id = ?",
                                Long.class,
                                historical))
                .isZero();
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .get("/api/v1/order-reservation-repairs/" + historical + "/terminal-dispositions")
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELLED"))
                .body("lines.eligible", equalTo(List.of(true, true)));
    }

    private void command(UUID order, String operation, int expected) {
        String token =
                operation.equals("place") || operation.equals("cancel")
                        ? TestSecurityConfig.aliceToken()
                        : TestSecurityConfig.adminToken();
        var response =
                given().auth()
                        .oauth2(token)
                        .contentType("application/json")
                        .post("/api/v1/orders/" + order + "/" + operation)
                        .then()
                        .statusCode(expected);
        if (expected == 409)
            response.contentType("application/problem+json").body("status", equalTo(409));
    }

    private Map<String, Object> snapshot(UUID order) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "order",
                jdbc.queryForMap("SELECT * FROM orders.purchase_order WHERE id = ?", order));
        for (String table :
                List.of(
                        "order_line",
                        "order_status_history",
                        "checkout_attempt",
                        "cleanup_task",
                        "fulfillment_request",
                        "fulfillment_task",
                        "reservation_reference_repair",
                        "terminal_reservation_reference_disposition")) {
            // Identifiers are this fixed test-owned list; values always use prepared parameters.
            String key =
                    table.equals("checkout_attempt") || table.equals("fulfillment_request")
                            ? "order_id"
                            : "id";
            result.put(
                    table,
                    jdbc.queryForList(
                            "SELECT * FROM orders." + table + " WHERE order_id = ? ORDER BY " + key,
                            order));
        }
        result.put(
                "outbox",
                jdbc.queryForList(
                        "SELECT * FROM orders.outbox_event WHERE aggregate_id = ? ORDER BY id",
                        order.toString()));
        return result;
    }

    private Fixture createTwoLineDraft() {
        String customer = "00000000-0000-0000-0000-000000000101";
        String first = "00000000-0000-0000-0000-000000000201";
        String second = "00000000-0000-0000-0000-000000000202";
        WireMockDownstreamConfig.stubCustomer(customer, "alice@omp.local", "Alice", "Customer");
        WireMockDownstreamConfig.stubProduct(first, "REF-FIRST", "First", "10.25", "USD");
        WireMockDownstreamConfig.stubProduct(second, "REF-SECOND", "Second", "20.50", "USD");
        var body =
                Map.of(
                        "customerId", customer,
                        "shippingAddress",
                                Map.of(
                                        "line1",
                                        "200 Shipping Ave",
                                        "city",
                                        "Oakland",
                                        "postalCode",
                                        "94607",
                                        "country",
                                        "US"),
                        "lines",
                                List.of(
                                        Map.of("productId", first, "quantity", 1),
                                        Map.of("productId", second, "quantity", 1)));
        UUID order =
                UUID.fromString(
                        given().auth()
                                .oauth2(TestSecurityConfig.aliceToken())
                                .contentType("application/json")
                                .body(body)
                                .post("/api/v1/orders")
                                .then()
                                .statusCode(201)
                                .extract()
                                .path("id"));
        var lines =
                jdbc.query(
                        "SELECT id, reservation_id FROM orders.order_line WHERE order_id = ? ORDER BY id",
                        (result, index) ->
                                new Line(
                                        result.getObject("id", UUID.class),
                                        result.getObject("reservation_id", UUID.class)),
                        order);
        UUID payment =
                jdbc.queryForObject(
                        "SELECT payment_id FROM orders.purchase_order WHERE id = ?",
                        UUID.class,
                        order);
        return new Fixture(order, lines, payment);
    }

    private record Line(UUID line, UUID reservation) {}

    private record Fixture(UUID order, List<Line> lines, UUID payment) {}
}
