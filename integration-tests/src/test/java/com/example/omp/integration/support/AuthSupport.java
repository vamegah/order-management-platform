package com.example.omp.integration.support;

import static io.restassured.RestAssured.given;

import java.time.Instant;
import java.util.Map;

/**
 * Acquires JWTs from Keycloak for the e2e tests.
 *
 * <p>Refreshes cached development credentials before expiry; never logs response bodies or tokens.
 */
public final class AuthSupport {

    private static final String KEYCLOAK_URL =
            System.getenv().getOrDefault("KEYCLOAK_URL", "http://keycloak.localhost");
    private static final String REALM = "omp";
    private static final String CLIENT_ID = "postman"; // password grant enabled

    private static Token aliceToken;
    private static Token adminToken;

    private AuthSupport() {}

    public static synchronized String aliceToken() {
        if (aliceToken == null || !aliceToken.refreshAt().isAfter(Instant.now())) {
            aliceToken = acquireToken("ALICE", "alice");
        }
        return aliceToken.value();
    }

    public static synchronized String adminToken() {
        if (adminToken == null || !adminToken.refreshAt().isAfter(Instant.now())) {
            adminToken = acquireToken("ADMIN", "admin");
        }
        return adminToken.value();
    }

    private static Token acquireToken(String name, String fallback) {
        var response =
                given().config(
                                io.restassured.config.RestAssuredConfig.config()
                                        .httpClient(
                                                io.restassured.config.HttpClientConfig
                                                        .httpClientConfig()
                                                        .setParam("http.connection.timeout", 5000)
                                                        .setParam("http.socket.timeout", 15000)))
                        .contentType("application/x-www-form-urlencoded")
                        .formParams(
                                Map.of(
                                        "grant_type", "password",
                                        "client_id",
                                                System.getenv()
                                                        .getOrDefault("E2E_CLIENT_ID", CLIENT_ID),
                                        "username",
                                                System.getenv()
                                                        .getOrDefault(
                                                                "E2E_" + name + "_USERNAME",
                                                                fallback),
                                        "password",
                                                System.getenv()
                                                        .getOrDefault(
                                                                "E2E_" + name + "_PASSWORD",
                                                                fallback + "-dev-password")))
                        .when()
                        .post(KEYCLOAK_URL + "/realms/" + REALM + "/protocol/openid-connect/token");

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Failed to acquire token for " + name + ": " + response.statusCode());
        }
        String value = response.jsonPath().getString("access_token");
        long expires = response.jsonPath().getLong("expires_in");
        if (value == null || value.isBlank() || expires <= 0) {
            throw new IllegalStateException("Issuer returned an invalid token response");
        }
        return new Token(value, Instant.now().plusSeconds(Math.max(1, expires - 30)));
    }

    private record Token(String value, Instant refreshAt) {
        @Override
        public String toString() {
            return "Token[redacted]";
        }
    }
}
