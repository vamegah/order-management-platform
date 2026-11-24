package com.example.omp.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProviderFixtureWorker {
    private final ProviderFixtureLedger ledger;
    private final SimulatorProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient transport =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public ProviderFixtureWorker(
            ProviderFixtureLedger ledger, SimulatorProperties properties, ObjectMapper mapper) {
        this.ledger = ledger;
        this.properties = properties;
        this.mapper = mapper;
    }

    private final java.util.concurrent.ThreadPoolExecutor executor =
            new java.util.concurrent.ThreadPoolExecutor(
                    4,
                    4,
                    0,
                    java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(16),
                    Thread.ofPlatform().name("provider-callback-", 0).daemon(true).factory());
    private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(20);

    @jakarta.annotation.PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    @Scheduled(fixedDelay = 100)
    public void deliver() {
        for (var callback : ledger.due(slots.availablePermits())) {
            if (!slots.tryAcquire()) break;
            try {
                executor.execute(() -> send(callback));
            } catch (java.util.concurrent.RejectedExecutionException stopping) {
                slots.release();
                ledger.acknowledged(callback, false);
            }
        }
    }

    private void send(ProviderFixtureLedger.Callback callback) {
        boolean success = false;
        try {
            String body = mapper.writeValueAsString(callback.payload());
            long timestamp = Instant.now().getEpochSecond();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            properties.getHmacSecret().getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"));
            String signature =
                    HexFormat.of()
                            .formatHex(
                                    mac.doFinal(
                                            (timestamp + "." + body)
                                                    .getBytes(StandardCharsets.UTF_8)));
            var request =
                    HttpRequest.newBuilder(URI.create(properties.getWebhookUrl()))
                            .timeout(Duration.ofSeconds(2))
                            .header("Content-Type", "application/json")
                            .header("Stripe-Signature", "t=" + timestamp + ",v1=" + signature)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            for (int i = 0; i <= callback.duplicates(); i++) {
                int status =
                        transport
                                .send(request, HttpResponse.BodyHandlers.discarding())
                                .statusCode();
                success = status >= 200 && status < 300;
                if (!success) break;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception unavailable) {
            /* No provider token, payload or signature is logged. */
        }
        try {
            ledger.acknowledged(callback, success);
        } finally {
            slots.release();
        }
    }
}
