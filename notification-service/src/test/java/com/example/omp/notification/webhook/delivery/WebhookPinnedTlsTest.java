package com.example.omp.notification.webhook.delivery;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.nio.file.*;
import java.security.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class WebhookPinnedTlsTest {
    @TempDir Path directory;

    @Test
    void pinnedIpPreservesOriginalTlsIdentityAndRejectsAnotherHostname() throws Exception {
        Path store = directory.resolve("subscriber.p12");
        String password = "disposable-fixture-password";
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var process =
                new ProcessBuilder(
                                keytool,
                                "-genkeypair",
                                "-alias",
                                "subscriber",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-dname",
                                "CN=callback.test",
                                "-ext",
                                "SAN=dns:callback.test",
                                "-validity",
                                "1",
                                "-keystore",
                                store.toString(),
                                "-storetype",
                                "PKCS12",
                                "-storepass",
                                password,
                                "-keypass",
                                password,
                                "-noprompt")
                        .redirectErrorStream(true)
                        .redirectOutput(directory.resolve("keytool.log").toFile())
                        .start();
        assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keys.load(input, password.toCharArray());
        }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password.toCharArray());
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keys);
        var serverContext = SSLContext.getInstance("TLS");
        serverContext.init(managers.getKeyManagers(), trust.getTrustManagers(), null);
        var clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, trust.getTrustManagers(), null);
        var server =
                com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(serverContext));
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext(
                "/hook",
                exchange -> {
                    accepted.incrementAndGet();
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        server.start();
        var env = new MockEnvironment();
        env.setActiveProfiles("test");
        var policy =
                new WebhookDestinationPolicy(env, "callback.test,other.test") {
                    @Override
                    public InetAddress[] resolve(String host) throws UnknownHostException {
                        return validateResolved(
                                host, new InetAddress[] {InetAddress.getByName("127.0.0.1")});
                    }
                };
        var deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try (var transport =
                new com.example.omp.notification.webhook.config.WebhookHttpClientConfig()
                        .webhookTransport(policy, clientContext)) {
            var client = new WebhookHttpClient(transport, policy, 3, deadlines);
            int port = server.getAddress().getPort();
            assertThat(
                            client.post(
                                            "https://callback.test:" + port + "/hook",
                                            "{}",
                                            java.util.Map.of())
                                    .outcome())
                    .isEqualTo(DeliveryOutcome.SUCCESS);
            assertThat(
                            client.post(
                                            "https://other.test:" + port + "/hook",
                                            "{}",
                                            java.util.Map.of())
                                    .outcome())
                    .isEqualTo(DeliveryOutcome.PERMANENT);
            assertThat(accepted.get()).isEqualTo(1);
        } finally {
            deadlines.shutdownNow();
            server.stop(0);
        }
    }
}
