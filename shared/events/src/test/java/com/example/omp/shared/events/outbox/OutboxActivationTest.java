package com.example.omp.shared.events.outbox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxActivationTest {
    private AnnotationConfigApplicationContext context(boolean repository, boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "test", java.util.Map.of("omp.outbox.enabled", enabled)));
        if (repository) {
            context.registerBean(OutboxRepository.class, () -> mock(OutboxRepository.class));
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class));
        }
        context.register(OutboxModuleAutoConfiguration.class);
        context.refresh();
        return context;
    }

    @Test
    void consumerWithoutOutboxRepositoryStartsWithoutOutboxBeans() {
        try (var context = context(false, true)) {
            assertThat(context.getBeansOfType(OutboxWriter.class)).isEmpty();
            assertThat(context.getBeansOfType(OutboxMetrics.class)).isEmpty();
            assertThat(context.getBeansOfType(OutboxDrainer.class)).isEmpty();
        }
    }

    @Test
    void disablingBackgroundPublishingKeepsTransactionalWriterAvailable() {
        try (var context = context(true, false)) {
            assertThat(context.getBean(OutboxWriter.class)).isNotNull();
            assertThat(context.getBean(OutboxMetrics.class)).isNotNull();
            assertThat(context.getBeansOfType(OutboxDrainer.class)).isEmpty();
        }
    }

    @Test
    void producerWithRepositoryEnablesDrainer() {
        try (var context = context(true, true)) {
            assertThat(context.getBean(OutboxWriter.class)).isNotNull();
            assertThat(context.getBean(OutboxDrainer.class)).isNotNull();
        }
    }
}
