package com.example.omp.integration.support;

import com.example.omp.proto.inventory.InventoryServiceGrpc;
import com.example.omp.proto.payment.PaymentServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Creates gRPC client stubs for the integration tests.
 *
 * <p>Channels connect to the running services via their exposed gRPC ports. For test environments,
 * the services run in Docker and are reachable via their host-mapped ports (or via the `app`
 * network if tests run in a container).
 *
 * <p>The stubs attach an `Authorization` header with the caller's JWT, matching how order-service
 * calls the services.
 */
public final class GrpcClientFactory {

    private static ManagedChannel inventoryChannel;
    private static ManagedChannel paymentChannel;

    private GrpcClientFactory() {}

    public static InventoryServiceGrpc.InventoryServiceBlockingStub inventoryClient(String token) {
        return InventoryServiceGrpc.newBlockingStub(getInventoryChannel())
                .withInterceptors(authInterceptor(token))
                .withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    public static PaymentServiceGrpc.PaymentServiceBlockingStub paymentClient(String token) {
        return PaymentServiceGrpc.newBlockingStub(getPaymentChannel())
                .withInterceptors(authInterceptor(token))
                .withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private static synchronized ManagedChannel getInventoryChannel() {
        if (inventoryChannel == null) {
            String host = System.getenv().getOrDefault("INVENTORY_GRPC_HOST", "127.0.0.1");
            int port =
                    Integer.parseInt(System.getenv().getOrDefault("INVENTORY_GRPC_PORT", "9091"));
            inventoryChannel = channel(host, port, "inventory-service");
        }
        return inventoryChannel;
    }

    private static synchronized ManagedChannel getPaymentChannel() {
        if (paymentChannel == null) {
            String host = System.getenv().getOrDefault("PAYMENT_GRPC_HOST", "127.0.0.1");
            int port = Integer.parseInt(System.getenv().getOrDefault("PAYMENT_GRPC_PORT", "9093"));
            paymentChannel = channel(host, port, "payment-service");
        }
        return paymentChannel;
    }

    private static ManagedChannel channel(String host, int port, String authority) {
        String enabled = System.getenv().getOrDefault("GRPC_TLS_ENABLED", "true");
        if ("false".equalsIgnoreCase(enabled)) {
            return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        }
        if (!"true".equalsIgnoreCase(enabled)) {
            throw new IllegalArgumentException("GRPC_TLS_ENABLED must be true or false");
        }
        try {
            var tls =
                    GrpcSslContexts.forClient()
                            .trustManager(
                                    certificate(
                                            "GRPC_CA_CERT_FILE", "infrastructure/tls/ca/ca.crt"))
                            .keyManager(
                                    certificate(
                                            "GRPC_CLIENT_CERT_FILE",
                                            "infrastructure/tls/order-service/tls.crt"),
                                    certificate(
                                            "GRPC_CLIENT_KEY_FILE",
                                            "infrastructure/tls/order-service/tls.key"))
                            .build();
            return NettyChannelBuilder.forAddress(host, port)
                    .overrideAuthority(authority)
                    .sslContext(tls)
                    .build();
        } catch (javax.net.ssl.SSLException failure) {
            throw new IllegalStateException("Cannot configure the e2e gRPC TLS identity", failure);
        }
    }

    private static java.io.File certificate(String variable, String fallback) {
        Path path = Path.of(System.getenv().getOrDefault(variable, fallback));
        if (!java.nio.file.Files.isRegularFile(path)) {
            throw new IllegalStateException("Missing TLS file; configure " + variable);
        }
        return path.toFile();
    }

    private static io.grpc.ClientInterceptor authInterceptor(String token) {
        return MetadataUtils.newAttachHeadersInterceptor(authMetadata(token));
    }

    private static Metadata authMetadata(String token) {
        Metadata metadata = new Metadata();
        if (token == null) return metadata;
        metadata.put(
                Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + token);
        return metadata;
    }

    /** Clean up channels at the end of a test suite. */
    public static synchronized void shutdown() throws InterruptedException {
        ManagedChannel inventory = inventoryChannel;
        ManagedChannel payment = paymentChannel;
        inventoryChannel = null;
        paymentChannel = null;
        try {
            close(inventory);
        } finally {
            close(payment);
        }
    }

    private static void close(ManagedChannel channel) throws InterruptedException {
        if (channel == null) return;
        try {
            if (!channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)) channel.shutdownNow();
        } catch (InterruptedException failure) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
            throw failure;
        }
    }
}
