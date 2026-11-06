package com.example.omp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class WebhookSenderTest {
    @Test
    void serializationFailureIsMeasuredWithoutSending() throws Exception {
        var mapper = mock(ObjectMapper.class);
        var payload = WebhookEventPayload.succeeded("evt_bad", "pi_fixture", BigDecimal.ONE, "USD");
        when(mapper.writeValueAsString(payload))
                .thenThrow(new JsonProcessingException("fixture") {});
        var metrics = new SimpleMeterRegistry();
        try {
            var sender =
                    new WebhookSender(
                            new SimulatorProperties(), RestClient.builder(), mapper, metrics);
            assertThat(sender.send(payload)).isFalse();
            assertThat(
                            metrics.counter(
                                            "simulator.webhook.send.errors",
                                            "reason",
                                            "serialization")
                                    .count())
                    .isEqualTo(1);
            assertThat(metrics.find("simulator.webhook.send.success").counter()).isNull();
        } finally {
            metrics.close();
        }
    }
}
