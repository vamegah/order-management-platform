package com.example.omp.inventory.config;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.proto.inventory.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.*;
import net.devh.boot.grpc.server.autoconfigure.GrpcServerSecurityAutoConfiguration;
import net.devh.boot.grpc.server.security.interceptors.DefaultAuthenticatingServerInterceptor;
import net.devh.boot.grpc.server.security.interceptors.ExceptionTranslatingServerInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;

/**
 * Verify production interceptor activation and caller-context propagation on a real getStock
 * thread.
 */
class GrpcAuthenticationConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    static class Tokens {
        @Bean
        JwtDecoder decoder() {
            return token -> {
                if (!"accepted".equals(token)) throw new BadJwtException("Invalid test identity");
                return Jwt.withTokenValue(token).header("alg", "test").subject("accepted").build();
            };
        }
    }

    @Test
    void authenticatesBusinessCallsRejectsMissingAndInvalidTokensAndClearsWorkerContext() {
        new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                SecurityAutoConfiguration.class,
                                WebMvcAutoConfiguration.class,
                                GrpcServerSecurityAutoConfiguration.class))
                .withUserConfiguration(
                        SecurityConfig.class, Tokens.class, InventoryHttpSecurityConfig.class)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(DefaultAuthenticatingServerInterceptor.class);
                            var worker = Executors.newSingleThreadExecutor();
                            String name = InProcessServerBuilder.generateName();
                            var service =
                                    new InventoryServiceGrpc.InventoryServiceImplBase() {
                                        @Override
                                        public void getStockLevel(
                                                GetStockLevelRequest request,
                                                StreamObserver<StockLevel> observer) {
                                            var authentication =
                                                    SecurityContextHolder.getContext()
                                                            .getAuthentication();
                                            observer.onNext(
                                                    StockLevel.newBuilder()
                                                            .setSku(authentication.getName())
                                                            .build());
                                            observer.onCompleted();
                                        }
                                    };
                            var server =
                                    InProcessServerBuilder.forName(name)
                                            .executor(worker)
                                            .addService(
                                                    ServerInterceptors.intercept(
                                                            service,
                                                            context.getBean(
                                                                    DefaultAuthenticatingServerInterceptor
                                                                            .class),
                                                            context.getBean(
                                                                    ExceptionTranslatingServerInterceptor
                                                                            .class)))
                                            .addService(
                                                    new io.grpc.protobuf.services
                                                                    .HealthStatusManager()
                                                            .getHealthService())
                                            .build()
                                            .start();
                            var channel =
                                    InProcessChannelBuilder.forName(name).directExecutor().build();
                            try {
                                var stub =
                                        InventoryServiceGrpc.newBlockingStub(channel)
                                                .withDeadlineAfter(3, TimeUnit.SECONDS);
                                var headers = new Metadata();
                                var authorization =
                                        Metadata.Key.of(
                                                "authorization", Metadata.ASCII_STRING_MARSHALLER);
                                headers.put(authorization, "Bearer accepted");
                                assertThat(
                                                stub.withInterceptors(
                                                                MetadataUtils
                                                                        .newAttachHeadersInterceptor(
                                                                                headers))
                                                        .getStockLevel(
                                                                GetStockLevelRequest
                                                                        .getDefaultInstance())
                                                        .getSku())
                                        .isEqualTo("accepted");
                                assertThatThrownBy(
                                                () ->
                                                        InventoryServiceGrpc.newBlockingStub(
                                                                        channel)
                                                                .withDeadlineAfter(
                                                                        3, TimeUnit.SECONDS)
                                                                .getStockLevel(
                                                                        GetStockLevelRequest
                                                                                .getDefaultInstance()))
                                        .isInstanceOfSatisfying(
                                                StatusRuntimeException.class,
                                                error ->
                                                        assertThat(error.getStatus().getCode())
                                                                .isEqualTo(
                                                                        Status.Code
                                                                                .UNAUTHENTICATED));
                                headers = new Metadata();
                                headers.put(authorization, "Bearer invalid");
                                var rejected =
                                        InventoryServiceGrpc.newBlockingStub(channel)
                                                .withInterceptors(
                                                        MetadataUtils.newAttachHeadersInterceptor(
                                                                headers))
                                                .withDeadlineAfter(3, TimeUnit.SECONDS);
                                assertThatThrownBy(
                                                () ->
                                                        rejected.getStockLevel(
                                                                GetStockLevelRequest
                                                                        .getDefaultInstance()))
                                        .isInstanceOfSatisfying(
                                                StatusRuntimeException.class,
                                                error ->
                                                        assertThat(error.getStatus().getCode())
                                                                .isEqualTo(
                                                                        Status.Code
                                                                                .UNAUTHENTICATED));
                                assertThat(
                                                worker.submit(
                                                                () ->
                                                                        SecurityContextHolder
                                                                                .getContext()
                                                                                .getAuthentication())
                                                        .get(3, TimeUnit.SECONDS))
                                        .isNull();
                                assertThat(
                                                io.grpc.health.v1.HealthGrpc.newBlockingStub(
                                                                channel)
                                                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                                                        .check(
                                                                io.grpc.health.v1.HealthCheckRequest
                                                                        .getDefaultInstance())
                                                        .getStatus())
                                        .isEqualTo(
                                                io.grpc.health.v1.HealthCheckResponse.ServingStatus
                                                        .SERVING);
                            } finally {
                                channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
                                server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
                                worker.shutdownNow();
                            }
                        });
    }
}
