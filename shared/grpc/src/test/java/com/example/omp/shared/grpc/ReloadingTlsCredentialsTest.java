package com.example.omp.shared.grpc;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import io.grpc.*;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReloadingTlsCredentialsTest {
    @TempDir static Path fixtures;
    @TempDir Path projection;
    static Path ca;
    private static final MethodDescriptor<String, String> ECHO =
            method("Echo", MethodDescriptor.MethodType.UNARY);
    private static final MethodDescriptor<String, String> WATCH =
            method("Watch", MethodDescriptor.MethodType.SERVER_STREAMING);

    @BeforeAll
    static void certificates() throws Exception {
        ca = fixtures.resolve("ca.crt");
        command(
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-keyout",
                fixtures.resolve("ca.key").toString(),
                "-out",
                ca.toString(),
                "-days",
                "30",
                "-subj",
                "/CN=Acceptance CA",
                "-addext",
                "basicConstraints=critical,CA:TRUE");
        leaf("first", "serverAuth,clientAuth");
        leaf("second", "serverAuth,clientAuth");
        leaf("wrong-usage", "serverAuth");
        leaf("elliptic", "serverAuth,clientAuth");
    }

    static void leaf(String name, String usage) throws Exception {
        var request = new ArrayList<String>(List.of("req", "-new", "-newkey"));
        if (name.equals("elliptic"))
            request.addAll(List.of("ec", "-pkeyopt", "ec_paramgen_curve:prime256v1"));
        else request.add("rsa:2048");
        request.addAll(
                List.of(
                        "-nodes",
                        "-keyout",
                        fixtures.resolve(name + ".key").toString(),
                        "-out",
                        fixtures.resolve(name + ".csr").toString(),
                        "-subj",
                        "/CN=localhost"));
        command(request.toArray(String[]::new));
        Path ext = fixtures.resolve(name + ".ext");
        Files.writeString(
                ext,
                "basicConstraints=CA:FALSE\nextendedKeyUsage="
                        + usage
                        + "\nsubjectAltName=DNS:localhost\n");
        command(
                "x509",
                "-req",
                "-in",
                fixtures.resolve(name + ".csr").toString(),
                "-CA",
                ca.toString(),
                "-CAkey",
                fixtures.resolve("ca.key").toString(),
                "-set_serial",
                name.equals("first") ? "1" : name.equals("second") ? "2" : "3",
                "-days",
                "30",
                "-extfile",
                ext.toString(),
                "-out",
                fixtures.resolve(name + ".crt").toString());
    }

    static void command(String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(System.getenv().getOrDefault("OPENSSL", "openssl"));
        command.addAll(List.of(arguments));
        var process =
                new ProcessBuilder(command)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    void project(String name) throws Exception {
        Files.copy(
                fixtures.resolve(name + ".crt"),
                projection.resolve("tls.crt"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(
                fixtures.resolve(name + ".key"),
                projection.resolve("tls.key"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(ca, projection.resolve("ca.crt"), StandardCopyOption.REPLACE_EXISTING);
    }

    ReloadingTlsCredentials credentials(Duration interval) throws Exception {
        return new ReloadingTlsCredentials(
                projection.resolve("tls.crt"),
                projection.resolve("tls.key"),
                projection.resolve("ca.crt"),
                interval);
    }

    @Test
    void rotatesBothHandshakeIdentitiesAutomaticallyWithoutInterruptingOpenStreams()
            throws Exception {
        project("first");
        var active = new AtomicReference<StreamObserver<String>>();
        var clientSerial = new AtomicReference<java.math.BigInteger>();
        try (var credentials = credentials(Duration.ofSeconds(1))) {
            var service =
                    ServerServiceDefinition.builder("acceptance.Tls")
                            .addMethod(
                                    ECHO,
                                    ServerCalls.asyncUnaryCall(
                                            (request, response) -> {
                                                response.onNext(request);
                                                response.onCompleted();
                                            }))
                            .addMethod(
                                    WATCH,
                                    ServerCalls.asyncServerStreamingCall(
                                            (request, response) -> {
                                                active.set(response);
                                                response.onNext("opened");
                                            }))
                            .build();
            var server =
                    NettyServerBuilder.forPort(0)
                            .sslContext(credentials.serverContext())
                            .intercept(
                                    new ServerInterceptor() {
                                        @Override
                                        public <Q, S> ServerCall.Listener<Q> interceptCall(
                                                ServerCall<Q, S> call,
                                                Metadata headers,
                                                ServerCallHandler<Q, S> next) {
                                            try {
                                                var session =
                                                        call.getAttributes()
                                                                .get(
                                                                        Grpc
                                                                                .TRANSPORT_ATTR_SSL_SESSION);
                                                clientSerial.set(
                                                        ((java.security.cert.X509Certificate)
                                                                        session.getPeerCertificates()[
                                                                                0])
                                                                .getSerialNumber());
                                            } catch (
                                                    javax.net.ssl.SSLPeerUnverifiedException
                                                            failure) {
                                                throw new AssertionError(failure);
                                            }
                                            return next.startCall(call, headers);
                                        }
                                    })
                            .addService(service)
                            .build()
                            .start();
            SslContext clientContext = credentials.clientContext();
            assertThat(ca.toFile()).exists();
            var old = channel(server.getPort(), clientContext);
            try {
                assertThat(echo(old)).isEqualTo("accepted");
                assertThat(clientSerial.get()).isEqualTo(java.math.BigInteger.ONE);
                var frames = new LinkedBlockingQueue<String>();
                var failure = new AtomicReference<Throwable>();
                ClientCalls.asyncServerStreamingCall(
                        old.newCall(
                                WATCH, CallOptions.DEFAULT.withDeadlineAfter(45, TimeUnit.SECONDS)),
                        "watch",
                        new StreamObserver<>() {
                            @Override
                            public void onNext(String frame) {
                                frames.add(frame);
                            }

                            @Override
                            public void onError(Throwable error) {
                                failure.set(error);
                            }

                            @Override
                            public void onCompleted() {}
                        });
                assertThat(frames.poll(5, TimeUnit.SECONDS)).isEqualTo("opened");
                project("second");
                await().atMost(Duration.ofSeconds(20))
                        .untilAsserted(
                                () -> {
                                    var fresh = channel(server.getPort(), clientContext);
                                    try {
                                        assertThat(echo(fresh)).isEqualTo("accepted");
                                        assertThat(clientSerial.get())
                                                .isEqualTo(java.math.BigInteger.TWO);
                                    } finally {
                                        fresh.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
                                    }
                                });
                active.get().onNext("after-renewal");
                assertThat(frames.poll(5, TimeUnit.SECONDS)).isEqualTo("after-renewal");
                assertThat(failure.get()).isNull();
                active.get().onCompleted();
            } finally {
                old.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void invalidOrPartialProjectionsRetainTheLastValidatedIdentityAndCanRecover() throws Exception {
        project("first");
        try (var credentials = credentials(Duration.ofHours(1))) {
            credentials.reload(); // unchanged projection is a no-op
            Files.copy(
                    fixtures.resolve("second.key"),
                    projection.resolve("tls.key"),
                    StandardCopyOption.REPLACE_EXISTING);
            assertThatThrownBy(credentials::reload).isInstanceOf(Exception.class);
            credentials.refresh(); // watcher rejects instead of exiting
            project("wrong-usage");
            assertThatThrownBy(credentials::reload)
                    .hasMessageContaining("serverAuth and clientAuth");
            project("first");
            Files.writeString(projection.resolve("ca.crt"), "");
            assertThatThrownBy(credentials::reload)
                    .hasMessageContaining("Empty certificate collection");
            project("first");
            Files.copy(
                    fixtures.resolve("first.crt"),
                    projection.resolve("ca.crt"),
                    StandardCopyOption.REPLACE_EXISTING);
            assertThatThrownBy(credentials::reload)
                    .hasMessageContaining("Trust anchor must be a CA");
            project("first");
            Files.writeString(projection.resolve("tls.key"), "not a PKCS8 key");
            assertThatThrownBy(credentials::reload).hasMessageContaining("PKCS8");
            project("second");
            credentials.reload();
            assertThat(credentials.serverContext()).isNotNull();
            assertThat(credentials.clientContext()).isNotNull();
        }
    }

    @Test
    void rejectsAProjectionReplacedDuringValidationAndAcceptsItsNextCoherentVersion()
            throws Exception {
        project("first");
        var changing = new java.util.concurrent.atomic.AtomicBoolean();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        Path certificate = projection.resolve("tls.crt");
        ReloadingTlsCredentials.ProjectionReader source =
                path -> {
                    if (changing.get() && path.equals(certificate) && reads.incrementAndGet() == 2)
                        return Files.readAllBytes(fixtures.resolve("first.crt"));
                    return Files.readAllBytes(path);
                };
        try (var credentials =
                new ReloadingTlsCredentials(
                        certificate,
                        projection.resolve("tls.key"),
                        projection.resolve("ca.crt"),
                        Duration.ofHours(1),
                        source)) {
            project("second");
            changing.set(true);
            assertThatThrownBy(credentials::reload)
                    .hasMessageContaining("projection changed during validation");
            changing.set(false);
            credentials.reload();
            assertThat(credentials.serverContext()).isNotNull();
        }
    }

    @Test
    void mutuallyAuthenticatesEllipticCurveIdentities() throws Exception {
        project("elliptic");
        try (var credentials = credentials(Duration.ofHours(1))) {
            var service =
                    ServerServiceDefinition.builder("acceptance.Tls")
                            .addMethod(
                                    ECHO,
                                    ServerCalls.asyncUnaryCall(
                                            (request, response) -> {
                                                response.onNext(request);
                                                response.onCompleted();
                                            }))
                            .build();
            var server =
                    NettyServerBuilder.forPort(0)
                            .sslContext(credentials.serverContext())
                            .addService(service)
                            .build()
                            .start();
            var caller = channel(server.getPort(), credentials.clientContext());
            try {
                assertThat(echo(caller)).isEqualTo("accepted");
            } finally {
                caller.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void replacesSessionCachesAndPreservesTheNettyContextContractAcrossReloads() throws Exception {
        project("first");
        try (var credentials = credentials(Duration.ofHours(1))) {
            var client = credentials.clientContext();
            var server = credentials.serverContext();
            var oldClientCache = client.sessionContext();
            var oldServerCache = server.sessionContext();
            var allocator = io.grpc.netty.shaded.io.netty.buffer.UnpooledByteBufAllocator.DEFAULT;
            java.util.concurrent.Executor executor = Runnable::run;
            for (var context : List.of(client, server)) {
                assertThat(context.cipherSuites()).isNotEmpty();
                assertThat(context.applicationProtocolNegotiator().protocols()).contains("h2");
                assertThat(context.sessionCacheSize()).isNotNegative();
                assertThat(context.sessionTimeout()).isNotNegative();
                assertThat(context.newEngine(allocator).getUseClientMode())
                        .isEqualTo(context.isClient());
                assertThat(context.newEngine(allocator, "localhost", 443).getUseClientMode())
                        .isEqualTo(context.isClient());
                assertThat(context.newHandler(allocator).engine().getUseClientMode())
                        .isEqualTo(context.isClient());
                assertThat(context.newHandler(allocator, executor).engine().getUseClientMode())
                        .isEqualTo(context.isClient());
                assertThat(
                                context.newHandler(allocator, "localhost", 443)
                                        .engine()
                                        .getUseClientMode())
                        .isEqualTo(context.isClient());
                assertThat(
                                context.newHandler(allocator, "localhost", 443, executor)
                                        .engine()
                                        .getUseClientMode())
                        .isEqualTo(context.isClient());
            }
            project("second");
            credentials.reload();
            assertThat(credentials.clientContext()).isSameAs(client);
            assertThat(credentials.serverContext()).isSameAs(server);
            assertThat(client.sessionContext()).isNotSameAs(oldClientCache);
            assertThat(server.sessionContext()).isNotSameAs(oldServerCache);
        }
    }

    @Test
    void refusesInvalidStartupAndUnsafePollIntervals() throws Exception {
        assertThatThrownBy(() -> credentials(Duration.ZERO)).hasMessageContaining("interval");
        assertThatThrownBy(() -> credentials(Duration.ofHours(2))).hasMessageContaining("interval");
        assertThatThrownBy(() -> credentials(Duration.ofSeconds(1)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void autoConfigurationIsOptInAndConfiguresBothNettyDirections() throws Exception {
        var runner =
                new ApplicationContextRunner()
                        .withConfiguration(AutoConfigurations.of(GrpcTlsAutoConfiguration.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(ReloadingTlsCredentials.class));
        project("first");
        runner.withPropertyValues(
                        "omp.grpc.tls.reload-enabled=true",
                        "TLS_CERT_PATH=" + projection.resolve("tls.crt"),
                        "TLS_KEY_PATH=" + projection.resolve("tls.key"),
                        "TLS_CA_PATH=" + projection.resolve("ca.crt"))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var server =
                                    context.getBean(
                                            net.devh.boot.grpc.server.serverfactory
                                                    .GrpcServerConfigurer.class);
                            server.accept(NettyServerBuilder.forPort(0));
                            var client =
                                    context.getBean(
                                            net.devh.boot.grpc.client.channelfactory
                                                    .GrpcChannelConfigurer.class);
                            client.accept(
                                    NettyChannelBuilder.forAddress("localhost", 1234),
                                    "inventory-service");
                            assertThatThrownBy(
                                            () ->
                                                    server.accept(
                                                            io.grpc.inprocess.InProcessServerBuilder
                                                                    .forName("unsupported")))
                                    .hasMessageContaining("shaded Netty");
                            assertThatThrownBy(
                                            () ->
                                                    client.accept(
                                                            io.grpc.inprocess
                                                                    .InProcessChannelBuilder
                                                                    .forName("unsupported"),
                                                            "test"))
                                    .hasMessageContaining("shaded Netty");
                        });
    }

    static ManagedChannel channel(int port, SslContext context) {
        return NettyChannelBuilder.forAddress("localhost", port).sslContext(context).build();
    }

    static String echo(ManagedChannel channel) {
        return ClientCalls.blockingUnaryCall(
                channel,
                ECHO,
                CallOptions.DEFAULT.withDeadlineAfter(15, TimeUnit.SECONDS),
                "accepted");
    }

    static MethodDescriptor<String, String> method(String name, MethodDescriptor.MethodType type) {
        MethodDescriptor.Marshaller<String> marshaller =
                new MethodDescriptor.Marshaller<>() {
                    @Override
                    public InputStream stream(String value) {
                        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
                    }

                    @Override
                    public String parse(InputStream input) {
                        try {
                            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    }
                };
        return MethodDescriptor.<String, String>newBuilder()
                .setType(type)
                .setFullMethodName("acceptance.Tls/" + name)
                .setRequestMarshaller(marshaller)
                .setResponseMarshaller(marshaller)
                .build();
    }
}
