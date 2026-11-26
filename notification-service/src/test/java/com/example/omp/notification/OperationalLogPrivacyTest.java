package com.example.omp.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.omp.notification.dispatcher.EventDispatcher;
import com.example.omp.notification.kafka.EventDeserializationSupport;
import com.example.omp.notification.redis.RedisEventSubscriber;
import com.example.omp.notification.subscription.*;
import com.example.omp.notification.websocket.SessionEventListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectedEvent;

class OperationalLogPrivacyTest {
    @Test
    void malformedEventLogsOnlyItsFailureClassAndNeverExceptionData() {
        var logger = (Logger) LoggerFactory.getLogger(RedisEventSubscriber.class);
        var capture = new ListAppender<ILoggingEvent>();
        capture.start();
        logger.addAppender(capture);
        try {
            var decoder = mock(EventDeserializationSupport.class);
            when(decoder.deserialize(anyString()))
                    .thenThrow(
                            new IllegalArgumentException("private-provider-token@example.invalid"));
            var subscriber =
                    new RedisEventSubscriber(
                            decoder,
                            mock(EventDispatcher.class),
                            new SimpleMeterRegistry(),
                            mock(OwnershipVerifier.class));
            var message = mock(Message.class);
            when(message.getChannel())
                    .thenReturn("notifications:orders".getBytes(StandardCharsets.UTF_8));
            when(message.getBody()).thenReturn("untrusted input".getBytes(StandardCharsets.UTF_8));
            subscriber.onMessage(message, null);
            assertThat(capture.list)
                    .hasSize(1)
                    .allSatisfy(
                            e -> {
                                assertThat(e.getFormattedMessage())
                                        .contains("IllegalArgumentException")
                                        .doesNotContain("private-provider-token");
                                assertThat(e.getThrowableProxy()).isNull();
                            });
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    @Test
    void sessionLifecycleDoesNotLogTheVerifiedPrincipal() {
        var logger = (Logger) LoggerFactory.getLogger(SessionEventListener.class);
        var capture = new ListAppender<ILoggingEvent>();
        capture.start();
        logger.addAppender(capture);
        try {
            var accessor = StompHeaderAccessor.create(StompCommand.CONNECTED);
            accessor.setSessionId("technical-session");
            accessor.setUser(() -> "private-identity@example.invalid");
            var event =
                    new SessionConnectedEvent(
                            this,
                            MessageBuilder.createMessage(
                                    new byte[0], accessor.getMessageHeaders()));
            new SessionEventListener(mock(SubscriptionRegistry.class)).onConnected(event);
            assertThat(capture.list)
                    .hasSize(1)
                    .allSatisfy(
                            e ->
                                    assertThat(e.getFormattedMessage())
                                            .contains("technical-session")
                                            .doesNotContain("private-identity"));
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }
}
