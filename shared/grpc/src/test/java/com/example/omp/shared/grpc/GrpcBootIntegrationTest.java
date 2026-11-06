package com.example.omp.shared.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.ClientInterceptors;
import io.grpc.ConnectivityState;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelFactory;
import net.devh.boot.grpc.server.security.authentication.GrpcAuthenticationReader;
import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
import net.devh.boot.grpc.server.service.GrpcService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class GrpcBootIntegrationTest {
    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    static final MethodDescriptor<String, String> ECHO =
            MethodDescriptor.<String, String>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("test.Echo/Call")
                    .setRequestMarshaller(new TextMarshaller())
                    .setResponseMarshaller(new TextMarshaller())
                    .build();

    static final class TextMarshaller implements MethodDescriptor.Marshaller<String> {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream input) {
            try {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    @GrpcService
    static class Echo implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            return ServerServiceDefinition.builder("test.Echo")
                    .addMethod(
                            ECHO,
                            ServerCalls.asyncUnaryCall(
                                    (request, response) -> {
                                        response.onNext(
                                                SecurityContextHolder.getContext()
                                                        .getAuthentication()
                                                        .getName());
                                        response.onCompleted();
                                    }))
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(Echo.class)
    static class Application {
        @Bean
        AuthenticationManager authenticationManager() {
            return token -> {
                if (!"accepted".equals(token.getPrincipal())) {
                    throw new BadCredentialsException("Invalid identity");
                }
                return UsernamePasswordAuthenticationToken.authenticated("caller", null, List.of());
            };
        }

        @Bean
        GrpcAuthenticationReader reader() {
            return (call, headers) -> {
                var value = headers.get(AUTHORIZATION);
                if (value == null) throw new BadCredentialsException("Identity required");
                return UsernamePasswordAuthenticationToken.unauthenticated(value, null);
            };
        }

        @Bean
        GrpcServerConfigurer workerConfigurer(ExecutorService worker) {
            return builder -> builder.executor(worker);
        }
    }

    @Test
    void discoversProductionInterceptorsAuthenticatesRealCallsClearsContextAndRecordsMetrics()
            throws Exception {
        String name = InProcessServerBuilder.generateName();
        var worker = Executors.newSingleThreadExecutor();
        try {
            new ApplicationContextRunner()
                    .withUserConfiguration(Application.class)
                    .withBean(ExecutorService.class, () -> worker)
                    .withPropertyValues(
                            "grpc.server.port=-1", "grpc.server.in-process-name=" + name)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context)
                                        .hasSingleBean(GrpcAuthenticationAutoConfiguration.class);
                                var channel =
                                        InProcessChannelBuilder.forName(name)
                                                .directExecutor()
                                                .build();
                                try {
                                    var headers = new Metadata();
                                    headers.put(AUTHORIZATION, "accepted");
                                    var authenticated =
                                            ClientInterceptors.intercept(
                                                    channel,
                                                    MetadataUtils.newAttachHeadersInterceptor(
                                                            headers));
                                    assertThat(
                                                    ClientCalls.blockingUnaryCall(
                                                            authenticated,
                                                            ECHO,
                                                            CallOptions.DEFAULT.withDeadlineAfter(
                                                                    3, TimeUnit.SECONDS),
                                                            "request"))
                                            .isEqualTo("caller");
                                    for (var value : new String[] {null, "invalid"}) {
                                        var rejected = new Metadata();
                                        if (value != null) rejected.put(AUTHORIZATION, value);
                                        var caller =
                                                ClientInterceptors.intercept(
                                                        channel,
                                                        MetadataUtils.newAttachHeadersInterceptor(
                                                                rejected));
                                        assertThatThrownBy(
                                                        () ->
                                                                ClientCalls.blockingUnaryCall(
                                                                        caller,
                                                                        ECHO,
                                                                        CallOptions.DEFAULT
                                                                                .withDeadlineAfter(
                                                                                        3,
                                                                                        TimeUnit
                                                                                                .SECONDS),
                                                                        "request"))
                                                .isInstanceOfSatisfying(
                                                        StatusRuntimeException.class,
                                                        error ->
                                                                assertThat(
                                                                                error.getStatus()
                                                                                        .getCode())
                                                                        .isEqualTo(
                                                                                Status.Code
                                                                                        .UNAUTHENTICATED));
                                    }
                                    assertThat(
                                                    worker.submit(
                                                                    () ->
                                                                            SecurityContextHolder
                                                                                    .getContext()
                                                                                    .getAuthentication())
                                                            .get(3, TimeUnit.SECONDS))
                                            .isNull();
                                    var registry = context.getBean(MeterRegistry.class);
                                    await().atMost(Duration.ofSeconds(3))
                                            .untilAsserted(
                                                    () ->
                                                            assertThat(
                                                                            registry
                                                                                    .getMeters()
                                                                                    .stream()
                                                                                    .filter(
                                                                                            Timer
                                                                                                            .class
                                                                                                    ::isInstance)
                                                                                    .map(
                                                                                            Timer
                                                                                                            .class
                                                                                                    ::cast)
                                                                                    .filter(
                                                                                            timer ->
                                                                                                    timer.getId()
                                                                                                            .getName()
                                                                                                            .startsWith(
                                                                                                                    "grpc.server"))
                                                                                    .mapToLong(
                                                                                            Timer
                                                                                                    ::count)
                                                                                    .sum())
                                                                    .isPositive());
                                } finally {
                                    channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
                                }
                            });
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void channelHealthTracksReadyAndTransientFailureAndRetainsChannelDetails() {
        var factory = mock(GrpcChannelFactory.class);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GrpcChannelHealthAutoConfiguration.class))
                .withBean(GrpcChannelFactory.class, () -> factory)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var indicator = context.getBean(HealthIndicator.class);
                            when(factory.getConnectivityState())
                                    .thenReturn(Map.of("inventory", ConnectivityState.READY));
                            assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
                            assertThat(indicator.health().getDetails())
                                    .containsEntry("inventory", ConnectivityState.READY);
                            when(factory.getConnectivityState())
                                    .thenReturn(
                                            Map.of(
                                                    "inventory",
                                                    ConnectivityState.TRANSIENT_FAILURE));
                            assertThat(indicator.health().getStatus().getCode())
                                    .isEqualTo("OUT_OF_SERVICE");
                            assertThat(indicator.health().getDetails())
                                    .containsEntry(
                                            "inventory", ConnectivityState.TRANSIENT_FAILURE);
                        });
    }

    @Test
    void channelHealthCanBeDisabledByTheExistingOperatorSetting() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GrpcChannelHealthAutoConfiguration.class))
                .withPropertyValues("management.health.grpcChannel.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(HealthIndicator.class);
                        });
    }
}
