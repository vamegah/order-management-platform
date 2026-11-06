package com.example.omp.notification.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for notification-service.
 *
 * <p>Binds to {@code omp.notifications.*} in application.yml.
 *
 * <p>All values have sensible defaults for dev. Production overrides the allowed origins (must be
 * an HTTPS origin) and possibly increases limits.
 */
@ConfigurationProperties(prefix = "omp.notifications")
public class NotificationProperties {

    /**
     * The STOMP endpoint path. Clients connect to {@code ws://host:port/ws} (or {@code wss://} in
     * prod).
     */
    private String endpointPath = "/ws";

    /**
     * Allowed origins for the WebSocket handshake. The browser sends an {@code Origin} header; the
     * server rejects connections from origins not in this list.
     *
     * <p>In dev, we allow localhost ports and localhost subdomains. In prod, only the deployed web
     * origin.
     */
    private List<String> allowedOrigins = List.of();

    /**
     * Interval between server-to-client heartbeats, in milliseconds. STOMP-level heartbeats; the
     * client sends its own at the same interval. A missed heartbeat triggers a disconnect.
     */
    private long heartbeatIntervalMs = 10_000;

    /**
     * Maximum number of concurrent WebSocket clients this replica accepts. Beyond this, new
     * connections are rejected.
     */
    private int maxConcurrentClients = 10_000;

    /** Maximum STOMP message size in bytes. Bounds memory per connection. */
    private int maxMessageSizeBytes = 65_536;

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public String getEndpointPath() {
        return endpointPath;
    }

    public void setEndpointPath(String endpointPath) {
        this.endpointPath = endpointPath;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public int getMaxConcurrentClients() {
        return maxConcurrentClients;
    }

    public void setMaxConcurrentClients(int maxConcurrentClients) {
        this.maxConcurrentClients = maxConcurrentClients;
    }

    public int getMaxMessageSizeBytes() {
        return maxMessageSizeBytes;
    }

    public void setMaxMessageSizeBytes(int maxMessageSizeBytes) {
        this.maxMessageSizeBytes = maxMessageSizeBytes;
    }
}
