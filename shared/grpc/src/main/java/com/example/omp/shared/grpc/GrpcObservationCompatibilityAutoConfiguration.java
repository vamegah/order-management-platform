package com.example.omp.shared.grpc;

import io.grpc.ClientInterceptor;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;

/** Keep standard RPC observations without constructing the registry inside a bean postprocessor. */
@AutoConfiguration(
        afterName =
                "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration")
@ConditionalOnClass(name = "net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor")
@ConditionalOnBean(ObservationRegistry.class)
public class GrpcObservationCompatibilityAutoConfiguration {
    @Bean
    @Order(2501)
    @net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor
    ClientInterceptor ompClientObservation(@Lazy ObservationRegistry observations) {
        return new ObservationGrpcClientInterceptor(observations);
    }
}
