package com.example.omp.shared.grpc;

import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.nio.file.Path;
import java.time.Duration;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelConfigurer;
import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

/** Install identical rotating mTLS policy for both Netty servers and outbound channels. */
@AutoConfiguration(
        beforeName = {
            "net.devh.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration",
            "net.devh.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration"
        })
@ConditionalOnProperty(name = "omp.grpc.tls.reload-enabled", havingValue = "true")
public class GrpcTlsAutoConfiguration {
    @Bean(destroyMethod = "close")
    public ReloadingTlsCredentials grpcTlsCredentials(Environment environment) throws Exception {
        return new ReloadingTlsCredentials(
                Path.of(environment.getProperty("TLS_CERT_PATH", "/etc/tls/identity/tls.crt")),
                Path.of(environment.getProperty("TLS_KEY_PATH", "/etc/tls/identity/tls.key")),
                Path.of(environment.getProperty("TLS_CA_PATH", "/etc/tls/trust/ca.crt")),
                environment.getProperty(
                        "omp.grpc.tls.poll-interval", Duration.class, Duration.ofSeconds(10)));
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(GrpcServerConfigurer.class)
    static class ServerConfiguration {
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        public GrpcServerConfigurer rotatingGrpcServer(ReloadingTlsCredentials credentials)
                throws Exception {
            var context = credentials.serverContext();
            return builder -> {
                if (!(builder instanceof NettyServerBuilder netty))
                    throw new IllegalStateException(
                            "Rotating mTLS requires the shaded Netty server");
                netty.sslContext(context);
            };
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(GrpcChannelConfigurer.class)
    static class ClientConfiguration {
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        public GrpcChannelConfigurer rotatingGrpcClient(ReloadingTlsCredentials credentials)
                throws Exception {
            var context = credentials.clientContext();
            return (builder, name) -> {
                if (!(builder instanceof NettyChannelBuilder netty))
                    throw new IllegalStateException("Rotating mTLS requires shaded Netty channels");
                netty.useTransportSecurity().sslContext(context);
            };
        }
    }
}
