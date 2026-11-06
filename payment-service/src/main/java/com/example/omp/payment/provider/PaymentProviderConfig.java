package com.example.omp.payment.provider;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans shared by provider implementations.
 *
 * <p>The provider implementations themselves are picked up by @ConditionalOnProperty on their own
 * classes.
 */
@Configuration
public class PaymentProviderConfig {

    /**
     * HTTP client used by StripeApiClient. Uses the JDK client; reuses connections, no redirect
     * following, HTTP/2.
     */
    @Bean
    public HttpClient stripeHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }
}
