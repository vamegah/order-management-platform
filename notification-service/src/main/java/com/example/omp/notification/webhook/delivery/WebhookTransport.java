package com.example.omp.notification.webhook.delivery;

/** A distinct bean type prevents the subscriber DNS policy becoming an internal Feign transport. */
public record WebhookTransport(org.apache.hc.client5.http.impl.classic.CloseableHttpClient client)
        implements AutoCloseable {
    @Override
    public void close() throws java.io.IOException {
        client.close();
    }
}
