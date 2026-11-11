package com.example.omp.order.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.equalTo;

import com.example.omp.order.AbstractIntegrationTest;
import com.example.omp.order.TestSecurityConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class OrderLifecycleControllerIT extends AbstractIntegrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void onlyAdministratorsCanInspectOrAdmitAuditedRetries() {
        UUID id = UUID.fromString(createDraft());
        String path = "/api/v1/order-lifecycle/" + id;
        given().get(path).then().statusCode(401);
        given().auth().oauth2(TestSecurityConfig.aliceToken()).get(path).then().statusCode(403);
        given().auth()
                .oauth2(TestSecurityConfig.aliceToken())
                .contentType("application/json")
                .body(Map.of("reason", "Retry"))
                .post(path + "/retry")
                .then()
                .statusCode(403);
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .get(path)
                .then()
                .statusCode(200)
                .body("status", equalTo("DRAFT"));
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType("application/json")
                .body(
                        Map.of(
                                "reason",
                                " Recheck verified inventory ownership ",
                                "actor",
                                "spoofed"))
                .post(path + "/retry")
                .then()
                .statusCode(202)
                .body("status", equalTo("RECONCILIATION_REQUESTED"));
        assertThat(
                        jdbc.queryForMap(
                                "SELECT actor, reason FROM orders.lifecycle_retry_audit WHERE order_id = ?",
                                id))
                .containsEntry("actor", "test-admin")
                .containsEntry("reason", "Recheck verified inventory ownership");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM orders.purchase_order WHERE id = ?",
                                String.class,
                                id))
                .isEqualTo("DRAFT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.cleanup_task WHERE order_id = ?",
                                Long.class,
                                id))
                .isZero();
    }

    @Test
    void invalidReasonsAndMissingOrdersNeverCreateRetryAudits() {
        UUID id = UUID.fromString(createDraft());
        for (Map<String, String> body :
                java.util.List.of(
                        Map.<String, String>of(),
                        Map.of("reason", " "),
                        Map.of("reason", "x".repeat(501))))
            given().auth()
                    .oauth2(TestSecurityConfig.adminToken())
                    .contentType("application/json")
                    .body(body)
                    .post("/api/v1/order-lifecycle/" + id + "/retry")
                    .then()
                    .statusCode(400);
        String missing = "/api/v1/order-lifecycle/" + UUID.randomUUID();
        given().auth().oauth2(TestSecurityConfig.adminToken()).get(missing).then().statusCode(404);
        given().auth()
                .oauth2(TestSecurityConfig.adminToken())
                .contentType("application/json")
                .body(Map.of("reason", "Retry"))
                .post(missing + "/retry")
                .then()
                .statusCode(404);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.lifecycle_retry_audit WHERE order_id = ?",
                                Long.class,
                                id))
                .isZero();
    }
}
