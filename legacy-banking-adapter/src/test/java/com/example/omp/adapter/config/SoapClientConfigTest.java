package com.example.omp.adapter.config;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class SoapClientConfigTest {
    SoapClientConfig config(String url) {
        var config = new SoapClientConfig();
        ReflectionTestUtils.setField(config, "bankWsUrl", url);
        return config;
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://bank.test/ws",
                "https://user@bank.test/ws",
                "https://bank.test/ws?token=private",
                "https://bank.test/ws#private",
                "file:/tmp/bank"
            })
    void rejectsUnsafeProductionLocations(String url) {
        assertThatThrownBy(
                        () ->
                                config(url)
                                        .messageSender(
                                                false,
                                                "",
                                                "",
                                                Duration.ofSeconds(1),
                                                Duration.ofSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void configuresBoundedTrustedTlsAndRejectsMissingTrustMaterial() throws Exception {
        assertThat(
                        config("https://bank.test/ws")
                                .messageSender(
                                        false,
                                        "",
                                        "",
                                        Duration.ofSeconds(1),
                                        Duration.ofSeconds(2)))
                .isInstanceOf(
                        org.springframework.ws.transport.http.HttpsUrlConnectionMessageSender
                                .class);
        assertThat(
                        config("http://bank.test/ws")
                                .messageSender(
                                        true, "", "", Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .isNotNull();
        assertThatThrownBy(
                        () ->
                                config("https://bank.test/ws")
                                        .messageSender(
                                                false,
                                                "file:/missing-bank-store.p12",
                                                "private",
                                                Duration.ofSeconds(1),
                                                Duration.ofSeconds(2)))
                .isInstanceOf(java.io.IOException.class);
        for (var duration :
                java.util.List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofSeconds(20)))
            assertThatThrownBy(
                            () ->
                                    config("https://bank.test/ws")
                                            .messageSender(
                                                    false, "", "", duration, Duration.ofSeconds(2)))
                    .isInstanceOf(IllegalStateException.class);
    }
}
