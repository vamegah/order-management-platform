package com.example.omp.notification.webhook.delivery;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class WebhookDestinationPolicyTest {
    static final java.util.concurrent.ScheduledExecutorService deadlines =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

    @org.junit.jupiter.api.AfterAll
    static void closeDeadlines() {
        deadlines.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0.0.0.0",
                "10.2.3.4",
                "127.1.2.3",
                "100.64.0.1",
                "169.254.169.254",
                "172.31.0.1",
                "192.168.0.1",
                "192.0.2.1",
                "198.18.0.1",
                "198.51.100.1",
                "203.0.113.1",
                "224.0.0.1",
                "255.255.255.255",
                "::",
                "::1",
                "fc00::1",
                "fe80::1",
                "ff02::1",
                "::ffff:127.0.0.1",
                "64:ff9b::a00:1",
                "2001:db8::1",
                "3fff::1",
                "2002:7f00:1::1"
            })
    void rejectsSpecialAndTransitionAddresses(String address) throws Exception {
        assertThat(WebhookDestinationPolicy.isPublic(InetAddress.getByName(address))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1.1.1.1",
                "8.8.8.8",
                "192.0.1.1",
                "192.2.1.1",
                "2606:4700:4700::1111",
                "2001:4860:4860::8888"
            })
    void permitsRoutedAddresses(String address) throws Exception {
        assertThat(WebhookDestinationPolicy.isPublic(InetAddress.getByName(address))).isTrue();
    }

    @Test
    void mixedDnsAnswersFailClosed() throws Exception {
        var policy = new WebhookDestinationPolicy(new MockEnvironment(), "");
        assertThatThrownBy(
                        () ->
                                policy.validateResolved(
                                        "subscriber.test",
                                        new InetAddress[] {
                                            InetAddress.getByName("8.8.8.8"),
                                            InetAddress.getByName("127.0.0.1")
                                        }))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void productionCannotOptIntoTestDestinations() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod", "test");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebhookDestinationPolicy(env, "localhost"));
    }

    @Test
    void rebindingIsDeniedAtTheActualConnection() throws Exception {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var policy =
                new WebhookDestinationPolicy(new MockEnvironment(), "") {
                    @Override
                    public InetAddress[] resolve(String host) throws UnknownHostException {
                        return validateResolved(
                                host,
                                new InetAddress[] {
                                    InetAddress.getByName(
                                            count.getAndIncrement() == 0 ? "8.8.8.8" : "127.0.0.1")
                                });
                    }
                };
        try (var transport =
                new com.example.omp.notification.webhook.config.WebhookHttpClientConfig()
                        .webhookTransport(policy)) {
            var result =
                    new WebhookHttpClient(transport, policy, 1, deadlines)
                            .post("https://subscriber.test/hook", "{}", java.util.Map.of());
            assertThat(result.outcome()).isNotEqualTo(DeliveryOutcome.SUCCESS);
            assertThat(count.get()).isGreaterThanOrEqualTo(2);
        }
    }
}
