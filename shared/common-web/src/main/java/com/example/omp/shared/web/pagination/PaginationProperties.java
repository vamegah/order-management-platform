package com.example.omp.shared.web.pagination;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pagination configuration. Binds to {@code app.pagination.*} in application.yml.
 *
 * <p>Registered as a bean by {@link com.example.omp.shared.web.config.SharedWebAutoConfiguration}.
 */
@ConfigurationProperties(prefix = "app.pagination")
public class PaginationProperties {

    /** Default page size when the client doesn't specify one. */
    private int defaultSize = 20;

    /** Maximum page size the client can request. */
    private int maxSize = 100;

    /** Fields the client can sort by. Others are rejected. */
    private List<String> allowedSortFields = List.of("createdAt", "updatedAt");

    public int getDefaultSize() {
        return defaultSize;
    }

    public void setDefaultSize(int defaultSize) {
        this.defaultSize = defaultSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public List<String> getAllowedSortFields() {
        return allowedSortFields;
    }

    public void setAllowedSortFields(List<String> allowedSortFields) {
        this.allowedSortFields = allowedSortFields;
    }

    // getters / setters identical to Phase 1's PaginationConfig

}
