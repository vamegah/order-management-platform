package com.example.omp.notification.webhook.delivery;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookHttpClient {
    private final CloseableHttpClient transport;
    private final WebhookDestinationPolicy policy;
    private final long timeout;
    private final java.util.concurrent.ScheduledExecutorService deadlines;

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookHttpClient(
            WebhookTransport transport,
            WebhookDestinationPolicy policy,
            @Value("${webhook.delivery.timeout-seconds:10}") long seconds,
            @org.springframework.beans.factory.annotation.Qualifier("webhookDeadlines")
                    java.util.concurrent.ScheduledExecutorService deadlines) {
        this(transport.client(), policy, seconds, deadlines);
    }

    public WebhookHttpClient(
            CloseableHttpClient transport,
            WebhookDestinationPolicy policy,
            long seconds,
            java.util.concurrent.ScheduledExecutorService deadlines) {
        if (seconds < 1 || seconds > 60)
            throw new IllegalArgumentException("Webhook timeout must be between 1 and 60 seconds");
        this.transport = transport;
        this.policy = policy;
        this.timeout = seconds;
        this.deadlines = deadlines;
    }

    public DeliveryResult post(String url, String body, Map<String, String> headers) {
        final java.net.URI destination;
        try {
            destination = policy.validate(url);
        } catch (IllegalArgumentException denied) {
            return DeliveryResult.permanentError("DESTINATION_DENIED");
        }
        var request = new HttpPost(destination);
        request.setConfig(
                RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(2))
                        .setResponseTimeout(Timeout.ofSeconds(timeout))
                        .build());
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        headers.forEach(request::setHeader);
        var deadline =
                deadlines.schedule(
                        () -> request.cancel(), timeout, java.util.concurrent.TimeUnit.SECONDS);
        try {
            return transport.execute(
                    request,
                    response -> {
                        int status = response.getCode();
                        String text = null;
                        if (response.getEntity() != null) {
                            var input = response.getEntity().getContent();
                            boolean truncated = false;
                            try {
                                byte[] bytes = input.readNBytes(4097);
                                truncated = bytes.length > 4096;
                                text =
                                        new String(
                                                bytes,
                                                0,
                                                Math.min(4096, bytes.length),
                                                StandardCharsets.UTF_8);
                            } finally {
                                if (truncated) {
                                    request.cancel();
                                    try {
                                        input.close();
                                    } catch (IOException ignored) {
                                    }
                                } else input.close();
                            }
                        }
                        if (status >= 200 && status < 300)
                            return DeliveryResult.success(status, text);
                        if (status == 408
                                || status == 429
                                || status == 401
                                || status == 403
                                || status >= 500) {
                            var header = response.getFirstHeader("Retry-After");
                            return DeliveryResult.retryable(
                                    status,
                                    text,
                                    retryAfter(
                                            header == null ? null : header.getValue(),
                                            Instant.now()));
                        }
                        return DeliveryResult.permanent(status, text);
                    });
        } catch (SSLException failure) {
            return DeliveryResult.permanentError("TLS_VERIFICATION_FAILED");
        } catch (IOException failure) {
            return DeliveryResult.retryableError("TRANSPORT_UNAVAILABLE");
        } catch (RuntimeException failure) {
            return DeliveryResult.retryableError("TRANSPORT_FAILURE");
        } finally {
            deadline.cancel(false);
        }
    }

    public static Long retryAfter(String value, Instant now) {
        if (value == null || value.length() > 128) return null;
        try {
            long millis;
            if (value.matches("[0-9]{1,10}"))
                millis = Math.multiplyExact(Long.parseLong(value), 1000);
            else
                millis =
                        Duration.between(
                                        now,
                                        ZonedDateTime.parse(
                                                        value, DateTimeFormatter.RFC_1123_DATE_TIME)
                                                .toInstant())
                                .toMillis();
            return Math.min(86_400_000L, Math.max(0, millis));
        } catch (Exception ignored) {
            return null;
        }
    }
}
