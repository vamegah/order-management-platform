package com.example.omp.shared.events.publisher;

import com.example.omp.shared.events.BaseEvent;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Base class for Kafka event publishers.
 *
 * <p>Encapsulates the common pattern:
 *
 * <ol>
 *   <li>Send the event to a topic, keyed by the aggregate ID.
 *   <li>Handle success (logging, metrics).
 *   <li>Handle failure (throw a domain exception, log, increment a metric).
 * </ol>
 *
 * <p>Subclasses provide the topic and any success/failure hooks. The publish method itself is final
 * — subclasses can't change the send semantics.
 *
 * <p><b>Key choice:</b> {@code event.aggregateId()} is the Kafka message key. Kafka hashes the key
 * to a partition. All events for one aggregate land in the same partition and are consumed in
 * order.
 *
 * <p><b>Failure handling:</b> the default behavior is to throw {@link EventPublishException}.
 * Acknowledgement is awaited for five seconds by default, with an optional constructor override.
 * This helper is not durable: use the transactional outbox for state-changing domain events.
 * Callers (typically orchestrating services) should call this from within an {@code afterCommit}
 * hook — see the batch design notes on transaction-synchronization.
 */
public abstract class KafkaEventPublisher {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final KafkaTemplate<String, BaseEvent> kafkaTemplate;
    private final String topic;
    private final Duration publishTimeout;

    protected KafkaEventPublisher(KafkaTemplate<String, BaseEvent> kafkaTemplate, String topic) {
        this(kafkaTemplate, topic, Duration.ofSeconds(5));
    }

    protected KafkaEventPublisher(
            KafkaTemplate<String, BaseEvent> kafkaTemplate, String topic, Duration publishTimeout) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
        this.topic = Objects.requireNonNull(topic, "topic");
        if (topic.isBlank()
                || publishTimeout == null
                || publishTimeout.isNegative()
                || publishTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("A topic and positive publish timeout are required");
        }
        this.publishTimeout = publishTimeout;
    }

    /**
     * Publishes an event to the configured topic.
     *
     * @throws EventPublishException if the send fails or times out
     */
    public final void publish(BaseEvent event) {
        Objects.requireNonNull(event, "event");
        org.springframework.kafka.support.SendResult<String, BaseEvent> result;
        try {
            result =
                    kafkaTemplate
                            .send(topic, event.aggregateId(), event)
                            .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            handleFailure(event, exception);
            return;
        } catch (ExecutionException exception) {
            handleFailure(event, exception.getCause());
            return;
        } catch (Exception e) {
            handleFailure(event, e);
            return;
        }
        handleSuccess(event, result.getRecordMetadata());
    }

    /**
     * Called on successful publish. Default: log at debug, no metrics. Subclasses can override to
     * add metrics or audit logging.
     */
    protected void handleSuccess(
            BaseEvent event, org.apache.kafka.clients.producer.RecordMetadata metadata) {
        log.debug(
                "Published {} eventId={} aggregateId={} to topic={} partition={} offset={}",
                event.eventType(),
                event.eventId(),
                event.aggregateId(),
                metadata.topic(),
                metadata.partition(),
                metadata.offset());
    }

    /**
     * Called on publish failure. Default: log and throw. Subclasses can override to add fallback
     * behavior (e.g., write to an outbox).
     */
    protected void handleFailure(BaseEvent event, Throwable ex) {
        log.error(
                "Failed to publish {} eventId={} aggregateId={} to topic={}: {}",
                event.eventType(),
                event.eventId(),
                event.aggregateId(),
                topic,
                ex.getMessage(),
                ex);
        throw new EventPublishException(
                "Failed to publish " + event.eventType() + " to " + topic, ex);
    }

    protected String topic() {
        return topic;
    }
}
