package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class OrderRuntimeConfigurationTest {
    @Test
    void persistenceAndJwtConfigurationUseSpringNamespace() throws Exception {
        var properties =
                new YamlPropertySourceLoader()
                        .load("order", new ClassPathResource("application.yml"))
                        .getFirst();
        assertThat(properties.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo(true);
        assertThat(properties.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                .isNotNull();
        assertThat(properties.getProperty("spring.datasource.hikari.maximum-pool-size"))
                .isEqualTo(10);
    }
}
