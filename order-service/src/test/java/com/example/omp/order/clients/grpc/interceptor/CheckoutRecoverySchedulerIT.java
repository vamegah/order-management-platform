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
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Spring scheduling, context restart and projection replacement over fixture RPCs. */
@Testcontainers
class CheckoutRecoverySchedulerIT {
    private static final String FIRST_TOKEN = "fixture.first.signature";
    private static final String SECOND_TOKEN = "fixture.second.signature";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine");

    @TempDir Path directory;
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private final AtomicInteger cancellations = new AtomicInteger();
    private final AtomicInteger ticks = new AtomicInteger();
    private final AtomicBoolean invalidIdentity = new AtomicBoolean();
    private final AtomicBoolean sawFirst = new AtomicBoolean();
    private final AtomicBoolean sawSecond = new AtomicBoolean();
    private final AtomicBoolean rpcInTransaction = new AtomicBoolean();
    private final AtomicBoolean leakedIdentity = new AtomicBoolean();
    private final AtomicReference<String> schedulerThread = new AtomicReference<>();
    private AnnotationConfigApplicationContext context;
    private SimpleMeterRegistry metrics;
    private JdbcTemplate jdbc;
    private DataSource source;
    private Path projection;
    private Server server;
    private ManagedChannel channel;

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void schedulerRestartsAndRecoversAfterProjectionReplacementWithoutManualTicks()
            throws Exception {
        source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE SCHEMA orders");
        jdbc.execute(
                "CREATE TABLE orders.purchase_order (id uuid PRIMARY KEY, status varchar(20) NOT NULL)");
        for (String migration :
                new String[] {"V7__durable_cleanup.sql", "V8__checkout_journal.sql"})
            jdbc.execute(
                    new ClassPathResource("db/migration/" + migration)
                            .getContentAsString(StandardCharsets.UTF_8));
        startEndpoints();
        projection = directory.resolve("access.jwt");
        project("invalid-projection");
        UUID first = orphan();
        startScheduler();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(counter("order.recovery.worker.failures"))
                                        .isGreaterThanOrEqualTo(2));
        assertUnclaimed(first);
        assertThat(calls).hasValue(0);
        project(FIRST_TOKEN);
        awaitCompletion(first, 2);
        assertCompensated(first);
        assertStableTicks(2, 1);
        closeScheduler();
        int callsBeforeRestart = calls.get();
        project("invalid-replacement");
        UUID second = orphan();
        startScheduler();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(counter("order.recovery.worker.failures"))
                                        .isGreaterThanOrEqualTo(2));
        assertUnclaimed(second);
        assertThat(calls).hasValue(callsBeforeRestart);
        assertThat(completedTasks()).isEqualTo(2);
        project(SECOND_TOKEN);
        awaitCompletion(second, 4);
        assertCompensated(first);
        assertCompensated(second);
        assertStableTicks(4, 2);
        assertThat(sawFirst).isTrue();
        assertThat(sawSecond).isTrue();
        assertThat(invalidIdentity).isFalse();
        assertThat(rpcInTransaction).isFalse();
        assertThat(leakedIdentity).isFalse();
        assertThat(schedulerThread.get()).startsWith("recovery-acceptance-");
        assertThat(ServiceTokenScope.current()).isNull();
    }

    private void startScheduler() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "fixture",
                                Map.of(
                                        "omp.order.recovery.enabled", "true",
                                        "omp.order.recovery.interval-ms", "100",
                                        "omp.order.recovery.token-file", projection.toString())));
        metrics = new SimpleMeterRegistry();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(MeterRegistry.class, () -> metrics);
        context.registerBean(
                InventoryClient.class,
                () ->
                        new InventoryClient(
                                InventoryServiceGrpc.newBlockingStub(channel),
                                new GrpcExceptionMapper()));
        context.registerBean(
                PaymentClient.class,
                () ->
                        new PaymentClient(
                                PaymentServiceGrpc.newBlockingStub(channel),
                                new GrpcExceptionMapper()));
        context.registerBean(
                "taskScheduler",
                ThreadPoolTaskScheduler.class,
                () -> {
                    var scheduler = new ThreadPoolTaskScheduler();
                    scheduler.setPoolSize(1);
                    scheduler.setThreadNamePrefix("recovery-acceptance-");
                    scheduler.setWaitForTasksToCompleteOnShutdown(true);
                    scheduler.setAwaitTerminationSeconds(5);
                    scheduler.setTaskDecorator(
                            task ->
                                    () -> {
                                        schedulerThread.set(Thread.currentThread().getName());
                                        try {
                                            task.run();
                                        } finally {
                                            if (ServiceTokenScope.current() != null)
                                                leakedIdentity.set(true);
                                            ticks.incrementAndGet();
                                        }
                                    });
                    return scheduler;
                });
        context.register(Transactions.class, CheckoutRecoveryJob.class);
        context.refresh();
        assertThat(context.getBean(CheckoutRecoveryJob.class)).isNotNull();
        assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                .hasSize(1);
    }

    private void closeScheduler() {
        if (context == null) return;
        var executor =
                context.getBean("taskScheduler", ThreadPoolTaskScheduler.class)
                        .getScheduledExecutor();
        context.close();
        context = null;
        assertThat(executor.isTerminated())
                .as("context shutdown terminates its scheduler")
                .isTrue();
        int stoppedAt = ticks.get();
        await().during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(ticks).hasValue(stoppedAt));
    }

    private void assertStableTicks(long tasks, int mutations) {
        int previousTicks = ticks.get();
        int previousCalls = calls.get();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(ticks.get()).isGreaterThanOrEqualTo(previousTicks + 3));
        assertThat(completedTasks()).isEqualTo(tasks);
        assertThat(counter("order.cleanup.completed", "RESERVATION")).isEqualTo(1);
        assertThat(counter("order.cleanup.completed", "PAYMENT")).isEqualTo(1);
        assertThat(counter("order.recovery.scans")).isEqualTo(1);
        assertThat(metrics.get("order.cleanup.pending").gauge().value()).isZero();
        assertThat(calls).hasValue(previousCalls);
        assertThat(releases).hasValue(mutations);
        assertThat(cancellations).hasValue(mutations);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.cleanup_task WHERE attempts <> 1 OR NOT completed OR completed_at IS NULL OR lease_id IS NOT NULL OR lease_until IS NOT NULL OR last_error IS NOT NULL",
                                Long.class))
                .isZero();
        assertThat(metrics.get("order.cleanup.pending").gauge().value()).isZero();
    }

    private void awaitCompletion(UUID order, long tasks) {
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () -> {
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT status FROM orders.checkout_attempt WHERE order_id = ?",
                                                    String.class,
                                                    order))
                                    .isEqualTo("RECOVERED");
                            assertThat(completedTasks()).isEqualTo(tasks);
                        });
    }

    private void assertUnclaimed(UUID order) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM orders.checkout_attempt WHERE order_id = ?",
                                String.class,
                                order))
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM orders.checkout_attempt WHERE order_id = ?",
                                Integer.class,
                                order))
                .isZero();
    }

    private long completedTasks() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM orders.cleanup_task WHERE completed", Long.class);
    }

    private double counter(String name, String... type) {
        var search = metrics.find(name);
        if (type.length == 1) search = search.tag("type", type[0]);
        var counter = search.counter();
        return counter == null ? 0 : counter.count();
    }

    private void assertCompensated(UUID order) {
        assertThat(reservations.values())
                .filteredOn(value -> value.getOrderId().equals(order.toString()))
                .singleElement()
                .satisfies(
                        value ->
                                assertThat(value.getStatus())
                                        .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED));
        assertThat(payments.values())
                .filteredOn(value -> value.getOrderId().equals(order.toString()))
                .singleElement()
                .satisfies(
                        value ->
                                assertThat(value.getStatus())
                                        .isEqualTo(PaymentStatus.PAYMENT_STATUS_CANCELLED));
    }

    private UUID orphan() {
        UUID order = UUID.randomUUID();
        String reservation = UUID.randomUUID().toString();
        reservations.put(
                reservation,
                Reservation.newBuilder()
                        .setId(reservation)
                        .setOrderId(order.toString())
                        .setSku("SKU-SCHEDULED")
                        .setWarehouseId("WH-1")
                        .setQuantity(1)
                        .setStatus(ReservationStatus.RESERVATION_STATUS_ACTIVE)
                        .build());
        String payment = UUID.randomUUID().toString();
        payments.put(
                payment,
                Payment.newBuilder()
                        .setId(payment)
                        .setOrderId(order.toString())
                        .setCustomerId(UUID.randomUUID().toString())
                        .setAmount(Money.newBuilder().setCurrency("USD").setAmount("12.34"))
                        .setStatus(PaymentStatus.PAYMENT_STATUS_PENDING)
                        .build());
        jdbc.update(
                "INSERT INTO orders.checkout_attempt (order_id, status, recover_after) VALUES (?, 'FAILED', now())",
                order);
        return order;
    }

    private void project(String token) throws Exception {
        Path temporary = Files.createTempFile(directory, "projection-", ".tmp");
        if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(temporary, token, StandardCharsets.US_ASCII);
        Files.move(
                temporary,
                projection,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private void startEndpoints() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .intercept(
                                new ServerInterceptor() {
                                    @Override
                                    public <Q, S> ServerCall.Listener<Q> interceptCall(
                                            ServerCall<Q, S> call,
                                            Metadata headers,
                                            ServerCallHandler<Q, S> next) {
                                        calls.incrementAndGet();
                                        String authorization =
                                                headers.get(
                                                        Metadata.Key.of(
                                                                "Authorization",
                                                                Metadata.ASCII_STRING_MARSHALLER));
                                        if (("Bearer " + FIRST_TOKEN).equals(authorization))
                                            sawFirst.set(true);
                                        else if (("Bearer " + SECOND_TOKEN).equals(authorization))
                                            sawSecond.set(true);
                                        else invalidIdentity.set(true);
                                        return next.startCall(call, headers);
                                    }
                                })
                        .addService(new InventoryEndpoint())
                        .addService(new PaymentEndpoint())
                        .build()
                        .start();
        channel =
                InProcessChannelBuilder.forName(name)
                        .directExecutor()
                        .intercept(
                                new AuthForwardingInterceptor(),
                                new ClientInterceptor() {
                                    @Override
                                    public <Q, S> ClientCall<Q, S> interceptCall(
                                            MethodDescriptor<Q, S> method,
                                            CallOptions options,
                                            Channel next) {
                                        if (TransactionSynchronizationManager
                                                .isActualTransactionActive())
                                            rpcInTransaction.set(true);
                                        return next.newCall(method, options);
                                    }
                                })
                        .build();
    }

    @AfterEach
    void close() throws Exception {
        try {
            closeScheduler();
        } finally {
            try {
                if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            } finally {
                if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    private static <T> void reply(StreamObserver<T> observer, T value) {
        observer.onNext(value);
        observer.onCompleted();
    }

    private class InventoryEndpoint extends InventoryServiceGrpc.InventoryServiceImplBase {
        @Override
        public void findReservationsByOrder(
                FindReservationsByOrderRequest request,
                StreamObserver<FindReservationsByOrderResponse> observer) {
            reply(
                    observer,
                    FindReservationsByOrderResponse.newBuilder()
                            .addAllReservations(
                                    reservations.values().stream()
                                            .filter(
                                                    value ->
                                                            value.getOrderId()
                                                                    .equals(request.getOrderId()))
                                            .toList())
                            .build());
        }

        @Override
        public void getReservation(
                GetReservationRequest request, StreamObserver<Reservation> observer) {
            reply(observer, reservations.get(request.getReservationId()));
        }

        @Override
        public void releaseReservation(
                ReleaseReservationRequest request, StreamObserver<Reservation> observer) {
            releases.incrementAndGet();
            reply(
                    observer,
                    reservations.computeIfPresent(
                            request.getReservationId(),
                            (id, value) ->
                                    value.toBuilder()
                                            .setStatus(
                                                    ReservationStatus.RESERVATION_STATUS_RELEASED)
                                            .build()));
        }
    }

    private class PaymentEndpoint extends PaymentServiceGrpc.PaymentServiceImplBase {
        @Override
        public void findPaymentsByOrder(
                FindPaymentsByOrderRequest request,
                StreamObserver<FindPaymentsByOrderResponse> observer) {
            reply(
                    observer,
                    FindPaymentsByOrderResponse.newBuilder()
                            .addAllPayments(
                                    payments.values().stream()
                                            .filter(
                                                    value ->
                                                            value.getOrderId()
                                                                    .equals(request.getOrderId()))
                                            .toList())
                            .build());
        }

        @Override
        public void getPayment(GetPaymentRequest request, StreamObserver<Payment> observer) {
            reply(observer, payments.get(request.getPaymentId()));
        }

        @Override
        public void cancelPayment(CancelPaymentRequest request, StreamObserver<Payment> observer) {
            cancellations.incrementAndGet();
            reply(
                    observer,
                    payments.computeIfPresent(
                            request.getPaymentId(),
                            (id, value) ->
                                    value.toBuilder()
                                            .setStatus(PaymentStatus.PAYMENT_STATUS_CANCELLED)
                                            .build()));
        }
    }

    @TestConfiguration
    @EnableScheduling
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
}
