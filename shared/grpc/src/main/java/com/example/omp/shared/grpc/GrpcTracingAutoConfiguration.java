package com.example.omp.shared.grpc;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration(
        afterName =
                "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration")
@ConditionalOnBean({Tracer.class, Propagator.class})
public class GrpcTracingAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor")
    static class Client {
        @Bean
        @net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor
        GrpcTraceInterceptors ompClientTracing(Tracer tracer, Propagator propagator) {
            return new GrpcTraceInterceptors(tracer, propagator);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor")
    static class Server {
        @Bean
        @net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor
        GrpcTraceInterceptors ompServerTracing(Tracer tracer, Propagator propagator) {
            return new GrpcTraceInterceptors(tracer, propagator);
        }
    }
}
