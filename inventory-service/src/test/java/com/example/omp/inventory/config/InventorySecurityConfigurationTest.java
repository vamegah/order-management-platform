package com.example.omp.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exercise the real decoder auto-configuration that was absent from the deployed service. */
class InventorySecurityConfigurationTest {
    private static HttpServer issuer;
    private static RSAKey signingKey;
    private static RSAKey foreignKey;
    private static String issuerUri;

    @BeforeAll
    static void startIssuer() throws Exception {
        signingKey = key();
        foreignKey = key();
        issuer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] jwks =
                new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        issuer.createContext(
                "/jwks",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, jwks.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(jwks);
                    }
                });
        issuer.start();
        issuerUri = "http://127.0.0.1:" + issuer.getAddress().getPort() + "/realm";
    }

    @AfterAll
    static void stopIssuer() {
        if (issuer != null) issuer.stop(0);
    }

    private static RSAKey key() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID("test-key")
                .build();
    }

    private static String token(RSAKey key, String issuerClaim, Instant expiry) {
        var claims =
                JwtClaimsSet.builder()
                        .issuer(issuerClaim)
                        .subject("inventory-caller")
                        .issuedAt(Instant.now().minusSeconds(300))
                        .expiresAt(expiry)
                        .build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build(),
                                claims))
                .getTokenValue();
    }

    private WebApplicationContextRunner context() {
        return new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                PropertyPlaceholderAutoConfiguration.class,
                                JacksonAutoConfiguration.class,
                                HttpMessageConvertersAutoConfiguration.class,
                                SecurityAutoConfiguration.class,
                                OAuth2ResourceServerAutoConfiguration.class,
                                WebMvcAutoConfiguration.class))
                .withUserConfiguration(
                        SecurityConfig.class,
                        InventoryHttpSecurityConfig.class,
                        ProbeConfiguration.class)
                .withPropertyValues(
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuerUri,
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:"
                                + issuer.getAddress().getPort()
                                + "/jwks");
    }

    @Test
    void createsProductionDecoderAndRejectsWrongIssuerSignatureAndExpiry() {
        context()
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(JwtDecoder.class)
                                    .hasSingleBean(JwtAuthenticationProvider.class);
                            var decoder = context.getBean(JwtDecoder.class);
                            assertThat(
                                            decoder.decode(
                                                            token(
                                                                    signingKey,
                                                                    issuerUri,
                                                                    Instant.now().plusSeconds(60)))
                                                    .getSubject())
                                    .isEqualTo("inventory-caller");
                            assertThatThrownBy(
                                            () ->
                                                    decoder.decode(
                                                            token(
                                                                    signingKey,
                                                                    issuerUri + "/other",
                                                                    Instant.now().plusSeconds(60))))
                                    .isInstanceOf(JwtException.class);
                            assertThatThrownBy(
                                            () ->
                                                    decoder.decode(
                                                            token(
                                                                    foreignKey,
                                                                    issuerUri,
                                                                    Instant.now().plusSeconds(60))))
                                    .isInstanceOf(JwtException.class);
                            assertThatThrownBy(
                                            () ->
                                                    decoder.decode(
                                                            token(
                                                                    signingKey,
                                                                    issuerUri,
                                                                    Instant.now()
                                                                            .minusSeconds(120))))
                                    .isInstanceOf(JwtException.class);
                        });
    }

    @Test
    void healthIsPublicWhileMetricsRequireAValidSignedToken() {
        context()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var mvc =
                                    MockMvcBuilders.webAppContextSetup(
                                                    context.getSourceApplicationContext())
                                            .apply(springSecurity())
                                            .build();
                            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
                            mvc.perform(get("/actuator/prometheus"))
                                    .andExpect(status().isUnauthorized());
                            mvc.perform(
                                            get("/actuator/prometheus")
                                                    .header(
                                                            "Authorization",
                                                            "Bearer "
                                                                    + token(
                                                                            signingKey,
                                                                            issuerUri,
                                                                            Instant.now()
                                                                                    .plusSeconds(
                                                                                            60))))
                                    .andExpect(status().isOk());
                        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping({"/actuator/health", "/actuator/prometheus"})
        String management() {
            return "ok";
        }
    }
}
