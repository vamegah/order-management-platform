package com.example.omp.order.clients.grpc.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.omp.order.clients.grpc.GrpcExceptionMapper;
import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.PaymentClient;
import com.example.omp.order.service.CheckoutJournal;
import com.example.omp.order.service.CheckoutRecoveryJob;
import com.example.omp.order.service.OrderCleanupQueue;
import com.example.omp.proto.common.Money;
import com.example.omp.proto.inventory.*;
import com.example.omp.proto.payment.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual worker JVM termination and elapsed cleanup lease recovery over loopback fixture RPCs. */
@Testcontainers
class CheckoutRecoveryProcessIT {
    private static final String TOKEN = "fixture.payload.signature";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine");

    @TempDir Path directory;
    private final List<Process> children = new ArrayList<>();
    private final AtomicReference<Reservation> remote = new AtomicReference<>();
    private final AtomicReference<Payment> remotePayment = new AtomicReference<>();
    private final AtomicReference<Refund> remoteRefund = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private final AtomicInteger cancellations = new AtomicInteger();
    private final AtomicInteger refunds = new AtomicInteger();
    private final AtomicBoolean forwardedIdentity = new AtomicBoolean(true);
    private final CountDownLatch mutationCommitted = new CountDownLatch(1);
    private Server server;
    private JdbcTemplate jdbc;
    private Path configuration;

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void killedWorkerLeavesALeaseAndANewJvmAcknowledgesTheLostReplyAfterRealExpiry()
            throws Exception {
        recoverAfterCrash(CrashPoint.RELEASE);
    }

    @ParameterizedTest(name = "{0} commits before the worker dies without its reply")
    @EnumSource(
            value = CrashPoint.class,
            names = {"CANCEL", "REFUND"})
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void killedPaymentWorkerRecoversAfterRealExpiryWithoutRepeatingTheMutation(CrashPoint point)
            throws Exception {
        recoverAfterCrash(point);
    }

    private enum CrashPoint {
        RELEASE,
        CANCEL,
        REFUND
    }

    private void recoverAfterCrash(CrashPoint point) throws Exception {
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()));
        // This database belongs exclusively to the static Testcontainers fixture.
        jdbc.execute("DROP SCHEMA IF EXISTS orders CASCADE");
        jdbc.execute("CREATE SCHEMA orders");
        jdbc.execute(
                "CREATE TABLE orders.purchase_order (id uuid PRIMARY KEY, status varchar(20) NOT NULL)");
        jdbc.execute(
                "CREATE TABLE orders.lifecycle_watch (order_id uuid PRIMARY KEY, compensation_ready boolean NOT NULL DEFAULT false)");
        for (String migration :
                new String[] {"V7__durable_cleanup.sql", "V8__checkout_journal.sql"})
            jdbc.execute(
                    new ClassPathResource("db/migration/" + migration)
                            .getContentAsString(StandardCharsets.UTF_8));
        UUID order = UUID.randomUUID();
        if (point == CrashPoint.RELEASE)
            remote.set(
                    Reservation.newBuilder()
                            .setId(UUID.randomUUID().toString())
                            .setOrderId(order.toString())
                            .setSku("SKU-CRASH")
                            .setWarehouseId("WH-1")
                            .setQuantity(1)
                            .setStatus(ReservationStatus.RESERVATION_STATUS_ACTIVE)
                            .build());
        else
            remotePayment.set(
                    Payment.newBuilder()
                            .setId(UUID.randomUUID().toString())
                            .setOrderId(order.toString())
                            .setCustomerId(UUID.randomUUID().toString())
                            .setAmount(Money.newBuilder().setCurrency("USD").setAmount("12.34"))
                            .setStatus(
                                    point == CrashPoint.CANCEL
                                            ? PaymentStatus.PAYMENT_STATUS_PENDING
                                            : PaymentStatus.PAYMENT_STATUS_CONFIRMED)
                            .setProviderReference(
                                    point == CrashPoint.REFUND ? "fixture-provider-reference" : "")
                            .build());
        jdbc.update(
                "INSERT INTO orders.checkout_attempt (order_id, status, recover_after) VALUES (?, 'FAILED', now())",
                order);
        server =
                NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                        .intercept(
                                new ServerInterceptor() {
                                    @Override
                                    public <Q, S> ServerCall.Listener<Q> interceptCall(
                                            ServerCall<Q, S> call,
                                            Metadata headers,
                                            ServerCallHandler<Q, S> next) {
                                        calls.incrementAndGet();
                                        if (!("Bearer " + TOKEN)
                                                .equals(
                                                        headers.get(
                                                                Metadata.Key.of(
                                                                        "Authorization",
                                                                        Metadata
                                                                                .ASCII_STRING_MARSHALLER))))
                                            forwardedIdentity.set(false);
                                        return next.startCall(call, headers);
                                    }
                                })
                        .addService(new InventoryEndpoint())
                        .addService(new PaymentEndpoint())
                        .build()
                        .start();
        configuration = directory.resolve("worker.properties");
        Path token = directory.resolve("synthetic-token");
        Files.writeString(token, TOKEN);
        Properties settings = new Properties();
        settings.setProperty("jdbc", POSTGRES.getJdbcUrl());
        settings.setProperty("user", POSTGRES.getUsername());
        settings.setProperty("password", POSTGRES.getPassword());
        settings.setProperty("port", Integer.toString(server.getPort()));
        settings.setProperty("token", token.toString());
        settings.setProperty("type", point == CrashPoint.RELEASE ? "RESERVATION" : "PAYMENT");
        try (var output = Files.newOutputStream(configuration)) {
            settings.store(output, "Ephemeral integration fixture only");
        }
        for (Path file : List.of(configuration, token))
            if (Files.getFileStore(file).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(
                        file,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

        Process first = startWorker("killed");
        assertThat(mutationCommitted.await(45, TimeUnit.SECONDS))
                .as("remote compensation committed before the deliberately withheld reply")
                .isTrue();
        assertThat(first.isAlive()).isTrue();
        first.destroyForcibly();
        assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(first.exitValue()).isNotZero();
        TaskState leased = task();
        assertThat(leased.completed()).isFalse();
        assertThat(leased.attempts()).isEqualTo(1);
        assertThat(leased.leaseId()).isNotNull();
        assertThat(leased.leaseUntil()).isNotNull();
        assertThat(leased.lastError()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT lease_until > now() FROM orders.cleanup_task",
                                Boolean.class))
                .isTrue();
        assertMutation(point);
        Reservation reservationAfterCrash = remote.get();
        Payment paymentAfterCrash = remotePayment.get();
        Refund refundAfterCrash = remoteRefund.get();
        int callsAfterCrash = calls.get();

        Properties early = finishWorker("before-expiry");
        assertThat(task()).isEqualTo(leased);
        assertThat(calls).hasValue(callsAfterCrash);
        assertThat(early.getProperty("completed")).isEqualTo("0.0");
        assertThat(early.getProperty("pending")).isEqualTo("1.0");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT lease_until > now() FROM orders.cleanup_task",
                                Boolean.class))
                .as("the replacement process ran before the original lease elapsed")
                .isTrue();

        // Observe PostgreSQL's real clock. Do not alter lease or retry timestamps.
        await().atMost(Duration.ofSeconds(135))
                .pollInterval(Duration.ofMillis(200))
                .until(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT lease_until < now() FROM orders.cleanup_task",
                                        Boolean.class));
        Properties recovered = finishWorker("after-expiry");
        TaskState completed = task();
        assertThat(completed.completed()).isTrue();
        assertThat(completed.attempts()).isEqualTo(2);
        assertThat(completed.leaseId()).isNull();
        assertThat(completed.leaseUntil()).isNull();
        assertThat(completed.lastError()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT completed_at IS NOT NULL FROM orders.cleanup_task",
                                Boolean.class))
                .isTrue();
        assertThat(recovered.getProperty("completed")).isEqualTo("1.0");
        assertThat(recovered.getProperty("pending")).isEqualTo("0.0");
        assertThat(calls).hasValue(callsAfterCrash + 1);
        assertMutation(point);
        Properties repeated = finishWorker("repeat");
        assertThat(task()).isEqualTo(completed);
        assertThat(calls).hasValue(callsAfterCrash + 1);
        assertMutation(point);
        assertThat(remote.get()).isEqualTo(reservationAfterCrash);
        assertThat(remotePayment.get()).isEqualTo(paymentAfterCrash);
        assertThat(remoteRefund.get()).isEqualTo(refundAfterCrash);
        assertThat(repeated.getProperty("completed")).isEqualTo("0.0");
        assertThat(forwardedIdentity).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM orders.checkout_attempt", String.class))
                .isEqualTo("RECOVERED");
    }

    private void assertMutation(CrashPoint point) {
        assertThat(releases).hasValue(point == CrashPoint.RELEASE ? 1 : 0);
        assertThat(cancellations).hasValue(point == CrashPoint.CANCEL ? 1 : 0);
        assertThat(refunds).hasValue(point == CrashPoint.REFUND ? 1 : 0);
        if (point == CrashPoint.RELEASE)
            assertThat(remote.get().getStatus())
                    .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
        else {
            assertThat(remotePayment.get().getStatus())
                    .isEqualTo(
                            point == CrashPoint.CANCEL
                                    ? PaymentStatus.PAYMENT_STATUS_CANCELLED
                                    : PaymentStatus.PAYMENT_STATUS_REFUNDED);
            if (point == CrashPoint.REFUND) {
                assertThat(remoteRefund.get().getPaymentId())
                        .isEqualTo(remotePayment.get().getId());
                assertThat(remoteRefund.get().getStatus())
                        .isEqualTo(RefundStatus.REFUND_STATUS_COMPLETED);
                assertThat(remoteRefund.get().getAmount())
                        .isEqualTo(remotePayment.get().getAmount());
            }
        }
    }

    @AfterEach
    void close() throws Exception {
        try {
            for (Process child : children)
                if (child.isAlive()) {
                    child.destroyForcibly();
                    assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
                }
        } finally {
            if (server != null) server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private Process startWorker(String name) throws Exception {
        String executable =
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path arguments = directory.resolve(name + ".args");
        Files.writeString(
                arguments,
                "-cp\n"
                        + argument(
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")))
                        + "\n"
                        + argument(WorkerProcess.class.getName())
                        + "\n"
                        + argument(configuration.toString())
                        + "\n"
                        + argument(directory.resolve(name + ".result").toString())
                        + "\n");
        Process process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", executable)
                                        .toString(),
                                "@" + arguments)
                        .redirectErrorStream(true)
                        .redirectOutput(directory.resolve(name + ".log").toFile())
                        .start();
        children.add(process);
        return process;
    }

    private static String argument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private Properties finishWorker(String name) throws Exception {
        Process process = startWorker(name);
        assertThat(process.waitFor(45, TimeUnit.SECONDS)).as("worker JVM tick terminates").isTrue();
        assertThat(process.exitValue())
                .withFailMessage(
                        "Worker JVM failed: %s", Files.readString(directory.resolve(name + ".log")))
                .isZero();
        Properties result = new Properties();
        try (var input = Files.newInputStream(directory.resolve(name + ".result"))) {
            result.load(input);
        }
        assertThat(result.getProperty("workerFailures")).isEqualTo("0.0");
        assertThat(result.getProperty("rpcInTransaction")).isEqualTo("false");
        assertThat(result.getProperty("identityCleared")).isEqualTo("true");
        return result;
    }

    private TaskState task() {
        return jdbc.queryForObject(
                "SELECT completed, attempts, lease_id, lease_until, last_error FROM orders.cleanup_task",
                (rs, row) ->
                        new TaskState(
                                rs.getBoolean("completed"),
                                rs.getInt("attempts"),
                                rs.getObject("lease_id", UUID.class),
                                rs.getTimestamp("lease_until") == null
                                        ? null
                                        : rs.getTimestamp("lease_until").toInstant(),
                                rs.getString("last_error")));
    }

    private record TaskState(
            boolean completed, int attempts, UUID leaseId, Instant leaseUntil, String lastError) {}

    private static <T> void reply(StreamObserver<T> observer, T value) {
        observer.onNext(value);
        observer.onCompleted();
    }

    private class InventoryEndpoint extends InventoryServiceGrpc.InventoryServiceImplBase {
        @Override
        public void findReservationsByOrder(
                FindReservationsByOrderRequest request,
                StreamObserver<FindReservationsByOrderResponse> observer) {
            var response = FindReservationsByOrderResponse.newBuilder();
            if (remote.get() != null) response.addReservations(remote.get());
            reply(observer, response.build());
        }

        @Override
        public void getReservation(
                GetReservationRequest request, StreamObserver<Reservation> observer) {
            reply(observer, remote.get());
        }

        @Override
        public void releaseReservation(
                ReleaseReservationRequest request, StreamObserver<Reservation> observer) {
            releases.incrementAndGet();
            remote.updateAndGet(
                    value ->
                            value.toBuilder()
                                    .setStatus(ReservationStatus.RESERVATION_STATUS_RELEASED)
                                    .build());
            mutationCommitted.countDown();
            // Acknowledge nothing: the parent kills the requesting JVM after the mutation.
        }
    }

    private class PaymentEndpoint extends PaymentServiceGrpc.PaymentServiceImplBase {
        @Override
        public void findPaymentsByOrder(
                FindPaymentsByOrderRequest request,
                StreamObserver<FindPaymentsByOrderResponse> observer) {
            var response = FindPaymentsByOrderResponse.newBuilder();
            if (remotePayment.get() != null) response.addPayments(remotePayment.get());
            reply(observer, response.build());
        }

        @Override
        public void getPayment(GetPaymentRequest request, StreamObserver<Payment> observer) {
            assertThat(request.getPaymentId()).isEqualTo(remotePayment.get().getId());
            reply(observer, remotePayment.get());
        }

        @Override
        public void cancelPayment(CancelPaymentRequest request, StreamObserver<Payment> observer) {
            assertThat(request.getPaymentId()).isEqualTo(remotePayment.get().getId());
            cancellations.incrementAndGet();
            remotePayment.updateAndGet(
                    value ->
                            value.toBuilder()
                                    .setStatus(PaymentStatus.PAYMENT_STATUS_CANCELLED)
                                    .build());
            mutationCommitted.countDown();
            // Deliberately lose the successful response across worker termination.
        }

        @Override
        public void refundPayment(RefundPaymentRequest request, StreamObserver<Refund> observer) {
            assertThat(request.getPaymentId()).isEqualTo(remotePayment.get().getId());
            assertThat(request.hasAmount()).as("worker requests the remaining balance").isFalse();
            assertThat(request.getIdempotencyKey())
                    .isEqualTo(
                            "order-cleanup-"
                                    + jdbc.queryForObject(
                                            "SELECT id FROM orders.cleanup_task", UUID.class));
            refunds.incrementAndGet();
            remoteRefund.set(
                    Refund.newBuilder()
                            .setId(UUID.randomUUID().toString())
                            .setPaymentId(request.getPaymentId())
                            .setAmount(remotePayment.get().getAmount())
                            .setStatus(RefundStatus.REFUND_STATUS_COMPLETED)
                            .build());
            remotePayment.updateAndGet(
                    value ->
                            value.toBuilder()
                                    .setStatus(PaymentStatus.PAYMENT_STATUS_REFUNDED)
                                    .build());
            mutationCommitted.countDown();
            // The fixture refund survives; the requesting worker does not receive its reply.
        }
    }

    @TestConfiguration
    @EnableTransactionManagement
    static class Transactions {
        @Bean
        JdbcTemplate jdbc(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        PlatformTransactionManager manager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        TransactionTemplate transactions(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }

        @Bean
        CheckoutJournal journal(JdbcTemplate jdbc) {
            return new CheckoutJournal(jdbc);
        }

        @Bean
        OrderCleanupQueue cleanup(
                JdbcTemplate jdbc,
                TransactionTemplate transactions,
                InventoryClient inventory,
                PaymentClient payments,
                MeterRegistry metrics) {
            return new OrderCleanupQueue(jdbc, transactions, inventory, payments, metrics);
        }
    }

    /**
     * Fork entry point uses production worker/clients/transactions without a scheduler or Boot app.
     */
    public static class WorkerProcess {
        public static void main(String[] args) throws Exception {
            Properties settings = new Properties();
            try (var input = Files.newInputStream(Path.of(args[0]))) {
                settings.load(input);
            }
            AtomicBoolean rpcInTransaction = new AtomicBoolean();
            ManagedChannel channel =
                    NettyChannelBuilder.forAddress(
                                    "127.0.0.1", Integer.parseInt(settings.getProperty("port")))
                            .usePlaintext()
                            .intercept(
                                    new AuthForwardingInterceptor(),
                                    new ClientInterceptor() {
                                        @Override
                                        public <Q, S> ClientCall<Q, S> interceptCall(
                                                io.grpc.MethodDescriptor<Q, S> method,
                                                CallOptions options,
                                                Channel next) {
                                            if (TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                                rpcInTransaction.set(true);
                                            return next.newCall(method, options);
                                        }
                                    })
                            .build();
            var metrics = new SimpleMeterRegistry();
            try (var context = new AnnotationConfigApplicationContext()) {
                var inventory =
                        new InventoryClient(
                                InventoryServiceGrpc.newBlockingStub(channel),
                                new GrpcExceptionMapper());
                var payments =
                        new PaymentClient(
                                PaymentServiceGrpc.newBlockingStub(channel),
                                new GrpcExceptionMapper());
                context.registerBean(
                        DataSource.class,
                        () ->
                                new DriverManagerDataSource(
                                        settings.getProperty("jdbc"),
                                        settings.getProperty("user"),
                                        settings.getProperty("password")));
                context.registerBean(InventoryClient.class, () -> inventory);
                context.registerBean(PaymentClient.class, () -> payments);
                context.registerBean(MeterRegistry.class, () -> metrics);
                context.register(Transactions.class);
                context.refresh();
                new CheckoutRecoveryJob(
                                context.getBean(CheckoutJournal.class),
                                context.getBean(OrderCleanupQueue.class),
                                inventory,
                                payments,
                                metrics,
                                Path.of(settings.getProperty("token")))
                        .run();
                Properties result = new Properties();
                var completed =
                        metrics.find("order.cleanup.completed")
                                .tag("type", settings.getProperty("type"))
                                .counter();
                var failures = metrics.find("order.recovery.worker.failures").counter();
                result.setProperty(
                        "completed", Double.toString(completed == null ? 0 : completed.count()));
                result.setProperty(
                        "pending",
                        Double.toString(metrics.get("order.cleanup.pending").gauge().value()));
                result.setProperty(
                        "workerFailures", Double.toString(failures == null ? 0 : failures.count()));
                result.setProperty("rpcInTransaction", Boolean.toString(rpcInTransaction.get()));
                result.setProperty(
                        "identityCleared", Boolean.toString(ServiceTokenScope.current() == null));
                try (var output = Files.newOutputStream(Path.of(args[1]))) {
                    result.store(output, "Worker process result; no credentials");
                }
            } finally {
                try {
                    metrics.close();
                } finally {
                    channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
                }
            }
        }
    }
}
