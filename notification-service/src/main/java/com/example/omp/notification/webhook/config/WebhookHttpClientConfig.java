package com.example.omp.notification.webhook.config;

import com.example.omp.notification.webhook.delivery.WebhookDestinationPolicy;
import java.net.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.*;
import org.springframework.context.annotation.*;

@Configuration
public class WebhookHttpClientConfig {
    @Bean(name = "webhookDeadlines", destroyMethod = "shutdown")
    public java.util.concurrent.ScheduledExecutorService deadlines() {
        var executor =
                new java.util.concurrent.ScheduledThreadPoolExecutor(
                        1, Thread.ofPlatform().name("webhook-deadline-", 0).daemon(true).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Bean(destroyMethod = "close")
    public com.example.omp.notification.webhook.delivery.WebhookTransport webhookTransport(
            WebhookDestinationPolicy policy) {
        return webhookTransport(policy, org.apache.hc.core5.ssl.SSLContexts.createSystemDefault());
    }

    public com.example.omp.notification.webhook.delivery.WebhookTransport webhookTransport(
            WebhookDestinationPolicy policy, javax.net.ssl.SSLContext trust) {
        var manager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(
                                new DnsResolver() {
                                    public InetAddress[] resolve(String host)
                                            throws UnknownHostException {
                                        return policy.resolve(host);
                                    }

                                    public String resolveCanonicalHostname(String host) {
                                        return host;
                                    }
                                })
                        .setTlsSocketStrategy(
                                org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder.create()
                                        .setSslContext(trust)
                                        .buildClassic())
                        .setMaxConnTotal(32)
                        .setMaxConnPerRoute(4)
                        .setDefaultConnectionConfig(
                                ConnectionConfig.custom()
                                        .setConnectTimeout(Timeout.ofSeconds(3))
                                        .setSocketTimeout(Timeout.ofSeconds(10))
                                        .setTimeToLive(TimeValue.ofSeconds(60))
                                        .build())
                        .build();
        // Original hostname stays in the route and TLS verification/SNI. The connection operator
        // receives only the addresses returned by the validating resolver; it never resolves again.
        return new com.example.omp.notification.webhook.delivery.WebhookTransport(
                HttpClients.custom()
                        .setConnectionManager(manager)
                        .disableRedirectHandling()
                        .disableAutomaticRetries()
                        .disableCookieManagement()
                        .disableAuthCaching()
                        .build());
    }
}
