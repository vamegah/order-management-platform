package com.example.omp.provider;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ProviderFixtureWorkerTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void signedDuplicateDeliveryAndFailedAcknowledgmentKeepOneProviderIdentity() throws Exception {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        var ledger = new ProviderFixtureLedger(mapper, environment, directory.toString());
        ledger.configure(mapper.readTree("{\"delayMs\":0,\"duplicateCallbacks\":1}"));
        var received = new LinkedBlockingQueue<Map<String, String>>();
        var status = new AtomicInteger(503);
        var server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/callback",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    int responseStatus = status.get();
                    received.add(
                            Map.of(
                                    "body",
                                    body,
                                    "signature",
                                    exchange.getRequestHeaders().getFirst("Stripe-Signature")));
                    exchange.sendResponseHeaders(responseStatus, -1);
                    exchange.close();
                });
        server.start();
        var properties = new SimulatorProperties();
        properties.setWebhookUrl("http://localhost:" + server.getAddress().getPort() + "/callback");
        properties.setHmacSecret("private-test-fixture-key");
        var worker = new ProviderFixtureWorker(ledger, properties, mapper);
        try {
            ledger.charge(
                    Map.of(
                            "amount",
                            "1000",
                            "currency",
                            "usd",
                            "payment_method",
                            "pm_success",
                            "metadata[payment_id]",
                            UUID.randomUUID().toString(),
                            "metadata[order_id]",
                            UUID.randomUUID().toString(),
                            "metadata[customer_id]",
                            UUID.randomUUID().toString()),
                    "stable-charge");
            worker.deliver();
            var first = received.poll(5, TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            String signature = first.get("signature");
            String timestamp = signature.split(",")[0].substring(2);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            properties.getHmacSecret().getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"));
            String expected =
                    HexFormat.of()
                            .formatHex(
                                    mac.doFinal(
                                            (timestamp + "." + first.get("body"))
                                                    .getBytes(StandardCharsets.UTF_8)));
            assertThat(signature).isEqualTo("t=" + timestamp + ",v1=" + expected);
            status.set(204);
            await().atMost(Duration.ofSeconds(5))
                    .until(
                            () -> {
                                worker.deliver();
                                return received.size() >= 2;
                            });
            var second = received.poll();
            var third = received.poll();
            assertThat(mapper.readTree(second.get("body")).path("id"))
                    .isEqualTo(mapper.readTree(first.get("body")).path("id"));
            assertThat(second.get("body")).isEqualTo(third.get("body"));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    new ProviderFixtureLedger(
                                                                    mapper,
                                                                    environment,
                                                                    directory.toString())
                                                            .stats()
                                                            .get("pendingCallbacks"))
                                            .isEqualTo(0L));
        } finally {
            worker.close();
            server.stop(0);
        }
    }

    @Test
    void invalidTransportAndShutdownReturnClaimsForRetry() throws Exception {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        var ledger = new ProviderFixtureLedger(mapper, environment, directory.toString());
        ledger.configure(mapper.readTree("{\"delayMs\":0}"));
        ledger.charge(
                Map.of(
                        "amount",
                        "1000",
                        "currency",
                        "usd",
                        "payment_method",
                        "pm_success",
                        "metadata[payment_id]",
                        UUID.randomUUID().toString(),
                        "metadata[order_id]",
                        UUID.randomUUID().toString(),
                        "metadata[customer_id]",
                        UUID.randomUUID().toString()),
                "retryable-transport");
        var properties = new SimulatorProperties();
        properties.setWebhookUrl("invalid transport URI");
        var worker = new ProviderFixtureWorker(ledger, properties, mapper);
        try {
            worker.deliver();
            final ProviderFixtureLedger.Callback[] retried = new ProviderFixtureLedger.Callback[1];
            await().atMost(Duration.ofSeconds(5))
                    .until(
                            () -> {
                                var due = ledger.due();
                                if (due.isEmpty()) return false;
                                retried[0] = due.getFirst();
                                return true;
                            });
            ledger.acknowledged(retried[0], false);
            worker.close();
            Thread.sleep(450);
            worker.deliver();
            assertThat(ledger.due()).isEmpty();
            await().atMost(Duration.ofSeconds(5)).until(() -> !ledger.due().isEmpty());
        } finally {
            worker.close();
        }
    }
}
