package com.example.omp.shared.web.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS configuration. Binds to {@code app.cors.*} in application.yml.
 *
 * <p>Registered as a bean by {@link SharedWebAutoConfiguration}.
 */
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /** Origins allowed to make cross-origin requests. */
    private List<String> allowedOrigins = List.of();

    /** Exposed response headers. */
    private List<String> exposedHeaders = List.of("X-Request-Id", "Location");

    /** Preflight cache duration in seconds. */
    private long maxAge = 3600;

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public List<String> getExposedHeaders() {
        return exposedHeaders;
    }

    public void setExposedHeaders(List<String> exposedHeaders) {
        this.exposedHeaders = exposedHeaders;
    }

    public long getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(long maxAge) {
        this.maxAge = maxAge;
    }

    // getters / setters

}
