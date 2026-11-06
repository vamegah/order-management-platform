package com.example.omp.shared.events.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.*;

class OutboxSchedulingTest {
    @Configuration
    @EnableScheduling
    static class Scheduling {}

    @Test
    void durationPropertiesRegisterTasksOnTheSupportedSpringVersion() {
        var repository = mock(OutboxRepository.class);
        var metrics = mock(OutboxMetrics.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var properties = new OutboxDrainerProperties();
        properties.setEnabled(false);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "test",
                                    Map.of(
                                            "omp.outbox.poll-interval",
                                            "5ms",
                                            "omp.outbox.cleanup-interval",
                                            "1h")));
            context.register(Scheduling.class);
            context.registerBean(
                    OutboxDrainer.class,
                    () -> new OutboxDrainer(repository, properties, metrics, kafka));
            context.refresh();
            assertThat(
                            context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                                    .getScheduledTasks())
                    .hasSize(2);
            verifyNoInteractions(repository, kafka);
        }
    }
}
