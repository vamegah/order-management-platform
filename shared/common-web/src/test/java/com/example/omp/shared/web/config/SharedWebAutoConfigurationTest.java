package com.example.omp.shared.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.omp.shared.web.error.ProblemDetailsFactory;
import com.example.omp.shared.web.filter.RequestIdFilter;
import com.example.omp.shared.web.pagination.PageableResolver;
import com.example.omp.shared.web.pagination.PaginationProperties;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

class SharedWebAutoConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(SharedWebAutoConfiguration.class));

    @Test
    void configuresPaginationWithoutRequiringTracing() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(PageableResolver.class);
                    assertThat(context).hasSingleBean(RequestIdFilter.class);
                    assertThat(context).hasSingleBean(ProblemDetailsFactory.class);
                    assertThat(
                                    context.getBean(ProblemDetailsFactory.class)
                                            .badRequest(
                                                    "invalid",
                                                    new org.springframework.mock.web
                                                            .MockHttpServletRequest())
                                            .traceId())
                            .isNull();
                    assertThat(context.getBean(PaginationProperties.class).getAllowedSortFields())
                            .containsExactly("createdAt", "updatedAt");
                });
    }

    @Test
    void bindsCorsAndPaginationAndRegistersTracingUtilities() {
        runner.withBean(Tracer.class, () -> mock(Tracer.class))
                .withPropertyValues(
                        "app.pagination.default-size=7",
                        "app.pagination.max-size=30",
                        "app.pagination.allowed-sort-fields=name,createdAt",
                        "app.cors.allowed-origins=https://shop.example",
                        "app.cors.exposed-headers=X-Request-Id",
                        "app.cors.max-age=60")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(RequestIdFilter.class);
                            assertThat(context).hasSingleBean(ProblemDetailsFactory.class);
                            assertThat(
                                            context.getBean(PageableResolver.class)
                                                    .resolve(null, null, "name")
                                                    .getPageSize())
                                    .isEqualTo(7);
                            assertThat(context.getBean(PaginationProperties.class).getMaxSize())
                                    .isEqualTo(30);
                            var registry = new InspectableCorsRegistry();
                            context.getBean(WebMvcConfigurer.class).addCorsMappings(registry);
                            var cors = registry.configuration();
                            assertThat(cors.getAllowedOrigins())
                                    .containsExactly("https://shop.example");
                            assertThat(cors.getExposedHeaders()).containsExactly("X-Request-Id");
                            assertThat(cors.getAllowedMethods())
                                    .containsExactly(
                                            "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
                            assertThat(cors.getAllowCredentials()).isTrue();
                            assertThat(cors.getMaxAge()).isEqualTo(60);
                        });
    }

    @Test
    void preservesServiceOverrides() {
        var resolver = mock(PageableResolver.class);
        var factory = mock(ProblemDetailsFactory.class);
        var filter = mock(RequestIdFilter.class);
        var cors = mock(WebMvcConfigurer.class);
        runner.withBean(Tracer.class, () -> mock(Tracer.class))
                .withBean(PageableResolver.class, () -> resolver)
                .withBean(ProblemDetailsFactory.class, () -> factory)
                .withBean(RequestIdFilter.class, () -> filter)
                .withBean("sharedCorsConfigurer", WebMvcConfigurer.class, () -> cors)
                .run(
                        context -> {
                            assertThat(context.getBean(PageableResolver.class)).isSameAs(resolver);
                            assertThat(context.getBean(ProblemDetailsFactory.class))
                                    .isSameAs(factory);
                            assertThat(context.getBean(RequestIdFilter.class)).isSameAs(filter);
                            assertThat(context.getBean(WebMvcConfigurer.class)).isSameAs(cors);
                        });
    }

    @Test
    void configuresCorsAlongsideUnrelatedMvcConfigurers() {
        runner.withBean(
                        "otherMvcConfigurer",
                        WebMvcConfigurer.class,
                        () -> new WebMvcConfigurer() {})
                .withPropertyValues("app.cors.allowed-origins=https://shop.example")
                .run(
                        context -> {
                            assertThat(context.getBeansOfType(WebMvcConfigurer.class)).hasSize(2);
                            var registry = new InspectableCorsRegistry();
                            context.getBean("sharedCorsConfigurer", WebMvcConfigurer.class)
                                    .addCorsMappings(registry);
                            assertThat(registry.configuration().getAllowedOrigins())
                                    .containsExactly("https://shop.example");
                        });
    }

    private static class InspectableCorsRegistry extends CorsRegistry {
        org.springframework.web.cors.CorsConfiguration configuration() {
            return getCorsConfigurations().get("/api/**");
        }
    }
}
