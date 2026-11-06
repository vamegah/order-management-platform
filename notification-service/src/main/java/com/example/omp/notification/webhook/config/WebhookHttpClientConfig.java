package com.example.omp.notification.webhook.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JDK HttpClient configured for outbound webhook delivery.
 *
 * <p>- connection pool managed by HttpClient - 10s connect timeout; per-request timeout is set on
 * the HttpRequest - no redirect following (a redirect host is usually a misconfiguration)
 */
@Configuration
public class WebhookHttpClientConfig {

    @Bean
    public HttpClient webhookTransport() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(
                        Executors.newFixedThreadPool(
                                16,
                                r -> {
                                    Thread t = new Thread(r, "webhook-http");
                                    t.setDaemon(true);
                                    return t;
                                }))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }
}
