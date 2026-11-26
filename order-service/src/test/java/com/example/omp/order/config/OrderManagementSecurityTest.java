package com.example.omp.order.config;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class OrderManagementSecurityTest {
    @Test
    void healthIsPublicAndMetricsNeedTheExplicitScopeOrVerifiedAdministratorRole() {
        new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                PropertyPlaceholderAutoConfiguration.class,
                                Jackson2AutoConfiguration.class,
                                HttpMessageConvertersAutoConfiguration.class,
                                SecurityAutoConfiguration.class,
                                WebMvcAutoConfiguration.class))
                .withUserConfiguration(SecurityConfig.class, Probe.class)
                .withBean(
                        JwtDecoder.class,
                        () ->
                                token -> {
                                    if (!List.of(
                                                    "consumer",
                                                    "scraper",
                                                    "administrator",
                                                    "uppercase-operator")
                                            .contains(token))
                                        throw new BadJwtException("Unrecognized fixture token");
                                    String role =
                                            token.equals("administrator")
                                                    ? "admin"
                                                    : token.equals("uppercase-operator")
                                                            ? "ADMIN"
                                                            : "customer";
                                    return Jwt.withTokenValue(token)
                                            .header("alg", "RS256")
                                            .issuer("https://fixture.invalid")
                                            .subject("verified-caller")
                                            .issuedAt(Instant.now())
                                            .expiresAt(Instant.now().plusSeconds(60))
                                            .claim(
                                                    "scope",
                                                    token.equals("scraper") ? "metrics.read" : "")
                                            .claim("realm_access", Map.of("roles", List.of(role)))
                                            .build();
                                })
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var mvc =
                                    MockMvcBuilders.webAppContextSetup(
                                                    context.getSourceApplicationContext())
                                            .apply(springSecurity())
                                            .build();
                            mvc.perform(get("/actuator/health/readiness"))
                                    .andExpect(status().isOk());
                            mvc.perform(get("/actuator/prometheus"))
                                    .andExpect(status().isUnauthorized());
                            mvc.perform(
                                            get("/actuator/prometheus")
                                                    .header("Authorization", "Bearer consumer"))
                                    .andExpect(status().isForbidden());
                            for (String token :
                                    List.of("scraper", "administrator", "uppercase-operator"))
                                mvc.perform(
                                                get("/actuator/prometheus")
                                                        .header("Authorization", "Bearer " + token))
                                        .andExpect(status().isOk());
                            mvc.perform(
                                            get("/actuator/prometheus")
                                                    .header("Authorization", "Bearer invalid"))
                                    .andExpect(status().isUnauthorized());
                        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Probe {
        @Bean
        Controller probeController() {
            return new Controller();
        }
    }

    @RestController
    static class Controller {
        @GetMapping({"/actuator/health/readiness", "/actuator/prometheus"})
        String get() {
            return "ok";
        }
    }
}
