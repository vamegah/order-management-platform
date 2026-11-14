package com.example.omp.notification.webhook;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import com.example.omp.notification.AbstractNotificationTest;
import com.example.omp.notification.support.TestTokens;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises production HTTP security, owner checks and committed PostgreSQL state. */
class WebhookManagementIT extends AbstractNotificationTest {
    static final String BASE = "/api/v1/webhook-subscriptions";
    @LocalServerPort int port;
    @Autowired WebhookSubscriptionRepository subscriptions;
    @Autowired JdbcTemplate jdbc;

    RequestSpecification as(String token) {
        return given().port(port).auth().oauth2(token).contentType("application/json");
    }

    Map<String, Object> create(String token) {
        return as(token)
                .body(
                        Map.of(
                                "name",
                                "contract-test",
                                "targetUrl",
                                "https://subscriber.example/hook",
                                "eventTypes",
                                List.of("payment.confirmed")))
                .post(BASE)
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getMap("");
    }

    @Test
    void missingAndInvalidCredentialsAreRejected() {
        given().port(port).get(BASE).then().statusCode(401);
        as("invalid-test-token").get(BASE).then().statusCode(401);
    }

    @Test
    void ownerCanManageSubscriptionAndOnlyCreationOrRotationExposeSecret() {
        var created = create(TestTokens.alice());
        String id = created.get("id").toString();
        String secret = created.get("secret").toString();
        assertThat(secret.length()).isGreaterThanOrEqualTo(32);
        assertThat(
                        jdbc.queryForObject(
                                "select owner_type from notifications.webhook_subscription where id = ?",
                                String.class,
                                UUID.fromString(id)))
                .isEqualTo("customer");
        as(TestTokens.alice())
                .get(BASE + "/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("secret", nullValue())
                .body("enabled", equalTo(true));
        as(TestTokens.alice())
                .get(BASE)
                .then()
                .statusCode(200)
                .body("id", hasItem(id))
                .body("secret", everyItem(nullValue()));
        as(TestTokens.alice())
                .body(Map.of("name", "updated", "eventTypes", List.of("order.placed")))
                .put(BASE + "/" + id)
                .then()
                .statusCode(200)
                .body("name", equalTo("updated"))
                .body("eventTypes", contains("order.placed"));
        String rotated =
                as(TestTokens.alice())
                        .post(BASE + "/" + id + "/rotate-secret")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("secret");
        assertThat(rotated).isNotEqualTo(secret);
        assertThat(subscriptions.findById(UUID.fromString(id)).orElseThrow().getSecret())
                .isEqualTo(rotated);
        as(TestTokens.alice()).delete(BASE + "/" + id).then().statusCode(204);
        var disabled = subscriptions.findById(UUID.fromString(id)).orElseThrow();
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.getDisabledAt()).isNotNull();
        as(TestTokens.alice())
                .post(BASE + "/" + id + "/enable")
                .then()
                .statusCode(200)
                .body("enabled", equalTo(true));
        var enabled = subscriptions.findById(UUID.fromString(id)).orElseThrow();
        assertThat(enabled.getDisabledAt()).isNull();
        assertThat(enabled.getFailureCount()).isZero();
    }

    @Test
    void foreignCustomerCannotReadMutateOrTestAnotherCustomersSubscription() {
        String id = create(TestTokens.alice()).get("id").toString();
        String path = BASE + "/" + id;
        as(TestTokens.bob()).get(path).then().statusCode(404);
        as(TestTokens.bob()).body(Map.of("name", "intrusion")).put(path).then().statusCode(404);
        as(TestTokens.bob()).delete(path).then().statusCode(404);
        for (String operation : List.of("test", "rotate-secret", "enable")) {
            as(TestTokens.bob()).post(path + "/" + operation).then().statusCode(404);
        }
        as(TestTokens.bob()).get(BASE).then().statusCode(200).body("id", not(hasItem(id)));
        as(TestTokens.bob())
                .queryParam("subscriptionId", id)
                .get("/api/v1/webhook-deliveries")
                .then()
                .statusCode(404);
        assertThat(subscriptions.findById(UUID.fromString(id)).orElseThrow().getName())
                .isEqualTo("contract-test");
    }

    @Test
    void nestedRealmAdminRoleCanInspectAnySubscriptionAndDlq() {
        String id = create(TestTokens.alice()).get("id").toString();
        as(TestTokens.admin()).get(BASE + "/" + id).then().statusCode(200).body("id", equalTo(id));
        as(TestTokens.admin()).get("/api/v1/webhook-dlq").then().statusCode(200);
        as(TestTokens.bob()).get("/api/v1/webhook-dlq").then().statusCode(403);
        as(TestTokens.alice())
                .post("/api/v1/webhook-dlq/" + UUID.randomUUID() + "/replay")
                .then()
                .statusCode(403);
        String adminId = create(TestTokens.admin()).get("id").toString();
        var adminSub = subscriptions.findById(UUID.fromString(adminId)).orElseThrow();
        assertThat(adminSub.getOwnerType()).isEqualTo(SubscriptionOwnerType.ADMIN);
        assertThat(adminSub.getOwnerId()).isEqualTo(TestTokens.ADMIN_ID);
    }

    @Test
    void replayAliasRequiresAdministratorAndPreservesNotFoundSemantics() {
        String path = "/api/v1/webhook-deliveries/" + UUID.randomUUID() + "/replay";
        given().port(port).post(path).then().statusCode(401);
        as(TestTokens.alice()).post(path).then().statusCode(403);
        as(TestTokens.admin()).post(path).then().statusCode(404);
    }

    @Test
    void invalidRequestAndMalformedIdHaveClientErrorResponses() {
        as(TestTokens.alice())
                .body(Map.of("name", "", "targetUrl", "file:///tmp/hook", "eventTypes", List.of()))
                .post(BASE)
                .then()
                .statusCode(400);
        as(TestTokens.alice()).get(BASE + "/not-a-uuid").then().statusCode(400);
        as(TestTokens.alice()).get(BASE + "/" + UUID.randomUUID()).then().statusCode(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=-5"})
    void invalidPaginationIsRejected(String query) {
        String id = create(TestTokens.alice()).get("id").toString();
        as(TestTokens.alice())
                .get("/api/v1/webhook-deliveries?subscriptionId=" + id + "&" + query)
                .then()
                .statusCode(400);
        as(TestTokens.admin()).get("/api/v1/webhook-dlq?" + query).then().statusCode(400);
    }
}
