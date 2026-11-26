package com.example.omp.shared.web.quota;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.quota")
public record QuotaProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("default") String namespace,
        @DefaultValue("redis") String host,
        @DefaultValue("6379") int port,
        @DefaultValue("") String username,
        Path passwordFile,
        @DefaultValue("false") boolean tls,
        @DefaultValue("250ms") Duration timeout,
        @DefaultValue("8192") int maxIdentities,
        @DefaultValue("1") double multiplier) {
    public QuotaProperties {
        if (namespace == null
                || !namespace.matches("[a-zA-Z0-9_-]{1,64}")
                || host == null
                || host.isBlank()
                || port < 1
                || port > 65535
                || timeout == null
                || timeout.toMillis() < 1
                || timeout.toMillis() > 2000
                || maxIdentities < 16
                || maxIdentities > 65536
                || !Double.isFinite(multiplier)
                || multiplier < 0.01
                || multiplier > 1000) {
            throw new IllegalArgumentException("Invalid shared quota configuration");
        }
    }
}
