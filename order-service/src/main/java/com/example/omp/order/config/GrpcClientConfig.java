package com.example.omp.order.config;

import com.example.omp.order.clients.grpc.interceptor.AuthForwardingInterceptor;
import com.example.omp.order.clients.grpc.interceptor.RequestIdInterceptor;
import net.devh.boot.grpc.client.interceptor.GlobalClientInterceptorConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers token and request ID forwarding before gRPC channels are created. */
@Configuration
public class GrpcClientConfig {
    @Bean
    public GlobalClientInterceptorConfigurer forwardingInterceptors(
            AuthForwardingInterceptor auth, RequestIdInterceptor requestId) {
        return interceptors -> {
            interceptors.add(auth);
            interceptors.add(requestId);
        };
    }
}
