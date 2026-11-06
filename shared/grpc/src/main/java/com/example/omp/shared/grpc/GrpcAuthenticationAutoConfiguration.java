package com.example.omp.shared.grpc;

import net.devh.boot.grpc.server.security.authentication.GrpcAuthenticationReader;
import net.devh.boot.grpc.server.security.interceptors.AuthenticatingServerInterceptor;
import net.devh.boot.grpc.server.security.interceptors.DefaultAuthenticatingServerInterceptor;
import net.devh.boot.grpc.server.security.interceptors.ExceptionTranslatingServerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.AuthenticationManager;

/** Retains the transport's caller-context interceptors without its removed voter API. */
@AutoConfiguration(
        beforeName = "net.devh.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration")
@ConditionalOnClass({DefaultAuthenticatingServerInterceptor.class, AuthenticationManager.class})
@ConditionalOnBean({AuthenticationManager.class, GrpcAuthenticationReader.class})
public class GrpcAuthenticationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ExceptionTranslatingServerInterceptor exceptionTranslatingServerInterceptor() {
        return new ExceptionTranslatingServerInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticatingServerInterceptor.class)
    public DefaultAuthenticatingServerInterceptor authenticatingServerInterceptor(
            AuthenticationManager manager, GrpcAuthenticationReader reader) {
        return new DefaultAuthenticatingServerInterceptor(manager, reader);
    }
}
