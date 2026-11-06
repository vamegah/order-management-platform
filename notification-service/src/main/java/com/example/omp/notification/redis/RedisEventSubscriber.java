package com.example.omp.notification.redis;

import com.example.omp.notification.dispatcher.EventDispatcher;
import com.example.omp.notification.kafka.EventDeserializationSupport;
import com.example.omp.shared.events.BaseEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Redis pub/sub subscriber for event channels.
 *
 * <p>Registered with the {@code RedisMessageListenerContainer} at startup (see {@link
 * RedisSubscriberRegistration}). When a message arrives on a channel, this listener:
 *
 * <ol>
 *   <li>Deserializes the JSON payload to a typed event.
 *   <li>Dispatches the event to local WebSocket subscribers.
 * </ol>
 *
 * <p><b>Registration and lifecycle:</b> this is a singleton bean; a single instance handles all
 * channels. The Redis container invokes {@link #onMessage} on a dedicated listener thread.
 *
 * <p><b>Error handling:</b> a deserialization failure or dispatch exception is logged and the
 * message is dropped. There's no retry — the original Kafka event has already been committed, and a
 * slow subscriber shouldn't block the Redis thread.
 */
@Component
public class RedisEventSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisEventSubscriber.class);

    private final EventDeserializationSupport deserializer;
    private final EventDispatcher dispatcher;
    private final MeterRegistry metrics;

    public RedisEventSubscriber(
            EventDeserializationSupport deserializer,
            EventDispatcher dispatcher,
            MeterRegistry metrics) {
        this.deserializer = deserializer;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        metrics.counter("redis.events.received", "channel", channel).increment();

        try {
            BaseEvent event = deserializer.deserialize(payload);
            if (event == null) {
                log.debug("Ignoring unknown or empty event on channel {}", channel);
                return;
            }
            dispatcher.dispatch(event);
        } catch (Exception e) {
            log.error(
                    "Failed to process Redis message on channel {}: {}",
                    channel,
                    e.getMessage(),
                    e);
            metrics.counter(
                            "redis.events.failed",
                            "channel",
                            channel,
                            "exception",
                            e.getClass().getSimpleName())
                    .increment();
        }
    }
}
