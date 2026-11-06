package com.example.omp.shared.grpc;

import io.grpc.ConnectivityState;
import java.util.Map;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

/** Preserves the starter's channel-health contract with Boot 4's health API. */
@AutoConfiguration(
        afterName = "net.devh.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration")
@ConditionalOnClass({GrpcChannelFactory.class, HealthIndicator.class})
@ConditionalOnEnabledHealthIndicator("grpcChannel")
public class GrpcChannelHealthAutoConfiguration {
    @Bean
    @Lazy
    @ConditionalOnMissingBean(name = "grpcChannelHealthIndicator")
    public HealthIndicator grpcChannelHealthIndicator(GrpcChannelFactory factory) {
        return () -> {
            var states = Map.copyOf(factory.getConnectivityState());
            var health =
                    states.containsValue(ConnectivityState.TRANSIENT_FAILURE)
                            ? Health.outOfService()
                            : Health.up();
            return health.withDetails(states).build();
        };
    }
}
