package com.example.omp.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("omp.graphql.protection")
public record GraphQlProtectionProperties(
        @DefaultValue("20") int maxDepth,
        @DefaultValue("2000") int maxComplexity,
        @DefaultValue("65536") int maxRequestBytes,
        @DefaultValue("120") int requestsPerWindow,
        @DefaultValue("10000") int maxIdentities,
        @DefaultValue("1m") Duration window,
        @DefaultValue("5s") Duration timeout) {
    public GraphQlProtectionProperties {
        if (maxDepth < 1
                || maxComplexity < 1
                || maxComplexity > 100000
                || maxRequestBytes < 1
                || maxRequestBytes > 10 * 1024 * 1024
                || requestsPerWindow < 1
                || maxIdentities < 1
                || window == null
                || window.toMillis() < 1
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero())
            throw new IllegalArgumentException(
                    "GraphQL limits must be positive; complexity cannot exceed 100000 and request bytes cannot exceed 10 MiB");
    }
}
