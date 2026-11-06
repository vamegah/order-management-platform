package com.example.omp.shared.web.config;

import com.example.omp.shared.web.error.ProblemDetailsFactory;
import com.example.omp.shared.web.filter.RequestIdFilter;
import com.example.omp.shared.web.pagination.PageableResolver;
import com.example.omp.shared.web.pagination.PaginationProperties;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Auto-configuration for shared web utilities.
 *
 * <p>Registered via {@code META-INF/spring/...AutoConfiguration.imports}. Spring Boot discovers
 * this class automatically when the module is on the classpath. Services get the beans without any
 * explicit configuration.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean} so a service can override any of them by
 * declaring its own bean of the same type. The CORS configurer is overridden by the bean name
 * {@code sharedCorsConfigurer}, so unrelated MVC configurers can coexist with it.
 */
@AutoConfiguration
@EnableConfigurationProperties({PaginationProperties.class, CorsProperties.class})
public class SharedWebAutoConfiguration {

    // -------------------------------------------------------------------------
    // Pagination
    // -------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public PageableResolver ompPageableResolver(PaginationProperties properties) {
        return new PageableResolver(properties);
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailsFactory problemDetailsFactory(ObjectProvider<Tracer> tracer) {
        return new ProblemDetailsFactory(tracer.getIfAvailable());
    }

    // -------------------------------------------------------------------------
    // Request correlation
    // -------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public RequestIdFilter requestIdFilter(ObjectProvider<Tracer> tracer) {
        return new RequestIdFilter(tracer.getIfAvailable());
    }

    // -------------------------------------------------------------------------
    // CORS
    // -------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean(name = "sharedCorsConfigurer")
    public WebMvcConfigurer sharedCorsConfigurer(CorsProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(properties.getAllowedOrigins().toArray(new String[0]))
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders(properties.getExposedHeaders().toArray(new String[0]))
                        .allowCredentials(true)
                        .maxAge(properties.getMaxAge());
            }
        };
    }
}
