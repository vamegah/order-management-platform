package com.example.omp.notification.config;

import com.example.omp.notification.subscription.SubscriptionInterceptor;
import com.example.omp.notification.websocket.StompAuthChannelInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * WebSocket and STOMP configuration.
 *
 * <p>Registers the STOMP endpoint, configures the in-memory broker, and wires the authentication
 * and subscription interceptors.
 *
 * <p><b>Interceptors (in order):</b>
 *
 * <ol>
 *   <li>{@link StompAuthChannelInterceptor} — authenticates CONNECT frames. Must run first so the
 *       Principal is set before SUBSCRIBE handling.
 *   <li>{@link SubscriptionInterceptor} — validates and registers SUBSCRIBE frames; cleans up on
 *       UNSUBSCRIBE.
 * </ol>
 *
 * <p><b>Broker:</b> a simple in-memory broker handles subscriptions locally. Cross-replica fan-out
 * is provided by Redis pub/sub (Batch 8.4).
 *
 * <p><b>Heartbeats:</b> the broker exchanges STOMP heartbeats with clients at the configured
 * interval. Two missed heartbeats trigger disconnect.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final NotificationProperties properties;
    private final StompAuthChannelInterceptor authInterceptor;
    private final SubscriptionInterceptor subscriptionInterceptor;

    public WebSocketConfig(
            NotificationProperties properties,
            StompAuthChannelInterceptor authInterceptor,
            SubscriptionInterceptor subscriptionInterceptor) {
        this.properties = properties;
        this.authInterceptor = authInterceptor;
        this.subscriptionInterceptor = subscriptionInterceptor;
    }

    /**
     * Task scheduler for STOMP heartbeats and in-memory broker timeouts. A dedicated pool keeps
     * WebSocket internals off the main executor.
     */
    @Bean
    public ThreadPoolTaskScheduler webSocketHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(
                        new long[] {
                            properties.getHeartbeatIntervalMs(), properties.getHeartbeatIntervalMs()
                        })
                .setTaskScheduler(webSocketHeartbeatScheduler());

        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setErrorHandler(new com.example.omp.notification.websocket.StompErrorHandler());
        registry.addEndpoint(properties.getEndpointPath())
                .setAllowedOrigins(properties.getAllowedOrigins().toArray(new String[0]))
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Order matters: auth must run before subscription handling.
        registration.interceptors(authInterceptor, subscriptionInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                // Max size of a single STOMP message (client → server).
                .setMessageSizeLimit(64 * 1024)
                // Max size of the outbound buffer per session. When exceeded,
                // Spring closes the session with a policy violation. The client
                // reconnects and re-syncs.
                .setSendBufferSizeLimit(256 * 1024)
                // Max time a message can sit in the outbound buffer before the
                // session is considered stuck and closed.
                .setSendTimeLimit(20 * 1000)
                // Max time to send the first message after the session is established.
                .setTimeToFirstMessage(30 * 1000);
    }
}
