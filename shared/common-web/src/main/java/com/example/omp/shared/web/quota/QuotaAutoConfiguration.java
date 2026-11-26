package com.example.omp.shared.web.quota;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(QuotaProperties.class)
public class QuotaAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.quota.enabled", havingValue = "true")
    @ConditionalOnMissingBean(QuotaStore.class)
    RedisQuotaStore quotaStore(QuotaProperties properties) throws java.io.IOException {
        return new RedisQuotaStore(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "app.quota.enabled", havingValue = "true")
    org.springframework.boot.ApplicationRunner quotaReadiness(QuotaStore store) {
        return arguments -> {
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    store.check();
                    return;
                } catch (RuntimeException unavailable) {
                    if (attempt < 4) Thread.sleep(200);
                }
            }
            // Never include driver exceptions that might contain a credential URI.
            throw new IllegalStateException("Shared quota store unavailable at startup");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    QuotaService quotaService(
            ObjectProvider<QuotaStore> store,
            QuotaProperties properties,
            ObjectProvider<MeterRegistry> metrics) {
        return new QuotaService(
                store.getIfAvailable(
                        () ->
                                (key, capacity) -> {
                                    throw new IllegalStateException("Quota store missing");
                                }),
                properties,
                metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    @ConditionalOnProperty(name = "app.quota.enabled", havingValue = "true")
    FilterRegistrationBean<QuotaFilter> quotaFilter(QuotaService quotas) {
        var registration = new FilterRegistrationBean<>(new QuotaFilter(quotas));
        registration.setOrder(-90); // Security's DelegatingFilterProxy is -100.
        registration.addUrlPatterns("/*");
        return registration;
    }
}
