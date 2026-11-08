package com.example.omp.order.clients.grpc.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.order.clients.grpc.GrpcExceptionMapper;
import com.example.omp.order.clients.grpc.InventoryClient;
import com.example.omp.order.clients.grpc.PaymentClient;
import com.example.omp.order.service.CheckoutJournal;
import com.example.omp.order.service.CheckoutRecoveryJob;
import com.example.omp.order.service.OrderCleanupQueue;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** Durable worker/queue integration with PostgreSQL and fault-injecting generated RPC endpoints. */
@Testcontainers
class CheckoutRecoveryWorkerIT {
    private static final String TOKEN = "fixture.payload.signature";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine");

    @TestConfiguration
    @EnableTransactionManagement
    static class Beans {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }

        @Bean
        JdbcTemplate jdbc(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
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

    @TempDir Path directory;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private CheckoutJournal journal;
    private OrderCleanupQueue cleanup;
    private InventoryClient inventory;
    private PaymentClient payments;
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private Server server;
    private ManagedChannel channel;
    private Path token;
    private Reservation reservation;
    private Payment payment;
    private boolean failPaymentDiscoveryOnce;
    private boolean loseReleaseReplyOnce;
    private boolean rpcInTransaction;
    private boolean validForwardedIdentity = true;
    private int rpcCalls;
    private int releases;
    private int cancellations;

    @BeforeAll
    static void schema() throws Exception {
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()));
        jdbc.execute("CREATE SCHEMA orders");
        jdbc.execute(
                "CREATE TABLE orders.purchase_order (id uuid PRIMARY KEY, status varchar(20) NOT NULL)");
        for (String migration :
                new String[] {"V7__durable_cleanup.sql", "V8__checkout_journal.sql"}) {
            jdbc.execute(
                    new ClassPathResource("db/migration/" + migration)
                            .getContentAsString(StandardCharsets.UTF_8));
        }
    }

    @BeforeEach
    void start() throws Exception {
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
                                        rpcCalls++;
                                        validForwardedIdentity &=
                                                ("Bearer " + TOKEN)
                                                        .equals(
                                                                headers.get(
                                                                        Metadata.Key.of(
                                                                                "Authorization",
                                                                                Metadata
                                                                                        .ASCII_STRING_MARSHALLER)));
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
                                        rpcInTransaction |=
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive();
                                        return next.newCall(method, options);
                                    }
                                })
                        .build();
        inventory =
                new InventoryClient(
                        InventoryServiceGrpc.newBlockingStub(channel), new GrpcExceptionMapper());
        payments =
                new PaymentClient(
                        PaymentServiceGrpc.newBlockingStub(channel), new GrpcExceptionMapper());
        context = new AnnotationConfigApplicationContext();
        context.registerBean(InventoryClient.class, () -> inventory);
        context.registerBean(PaymentClient.class, () -> payments);
        context.registerBean(MeterRegistry.class, () -> metrics);
        context.register(Beans.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        jdbc.execute(
                "TRUNCATE orders.cleanup_task, orders.checkout_attempt, orders.purchase_order");
        journal = context.getBean(CheckoutJournal.class);
        cleanup = context.getBean(OrderCleanupQueue.class);
        token = directory.resolve("synthetic-token");
        Files.writeString(token, TOKEN);
    }

    @AfterEach
    void close() throws Exception {
        try {
            if (context != null) context.close();
        } finally {
            try {
                if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            } finally {
                if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                metrics.close();
            }
        }
        assertThat(rpcInTransaction).as("RPCs execute outside database transactions").isFalse();
        assertThat(validForwardedIdentity).as("worker identity forwarding").isTrue();
        assertThat(ServiceTokenScope.current()).isNull();
    }

    @Test
    void partialDiscoveryPersistsVerifiedCleanupAndTheNextDueTickCompletesThePayment() {
        UUID order = failedCheckout(true);
        failPaymentDiscoveryOnce = true;
        worker().run();
        assertThat(status(order)).isEqualTo("RECOVERING");
        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_PENDING);
        assertThat(tasks(true)).isEqualTo(1);
        assertThat(counter("order.recovery.failures")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_error FROM orders.checkout_attempt WHERE order_id = ?",
                                String.class,
                                order))
                .isEqualTo("Recovery lookup failed");

        dueScan(order);
        worker().run();
        assertThat(status(order)).isEqualTo("RECOVERED");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_CANCELLED);
        assertThat(tasks(true)).isEqualTo(2);
        assertThat(tasks(false)).isZero();
        assertThat(metrics.get("order.cleanup.pending").gauge().value()).isZero();
        assertThat(counter("order.recovery.scans")).isEqualTo(1);

        dueScan(order);
        worker().run();
        assertThat(releases).isEqualTo(1);
        assertThat(cancellations).isEqualTo(1);
        assertThat(tasks(true)).isEqualTo(2);
        assertThat(counter("order.recovery.scans")).isEqualTo(2);
    }

    @Test
    void aLostReleaseReplyIsAcknowledgedByAFreshWorkerWithoutRepeatingTheMutation() {
        UUID order = failedCheckout(false);
        loseReleaseReplyOnce = true;
        worker().run();
        assertThat(status(order)).isEqualTo("RECOVERED");
        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
        assertThat(tasks(false)).isEqualTo(1);
        assertThat(tasks(true)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_error FROM orders.cleanup_task", String.class))
                .isEqualTo("DownstreamServiceException");
        assertThat(
                        metrics.get("order.cleanup.failures")
                                .tag("type", "RESERVATION")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(metrics.get("order.cleanup.pending").gauge().value()).isEqualTo(1);

        jdbc.update("UPDATE orders.cleanup_task SET next_attempt_at = now() - interval '1 second'");
        worker().run();
        assertThat(releases).isEqualTo(1);
        assertThat(tasks(true)).isEqualTo(1);
        assertThat(tasks(false)).isZero();
        assertThat(jdbc.queryForObject("SELECT attempts FROM orders.cleanup_task", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT last_error FROM orders.cleanup_task", String.class))
                .isNull();
        assertThat(
                        metrics.get("order.cleanup.completed")
                                .tag("type", "RESERVATION")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void liveLeasesExcludeASecondWorkerAndStaleCompletionCannotOverwriteReclaimedWork() {
        UUID order = failedCheckout(true);
        var old = journal.claim(1).getFirst();
        worker().run();
        assertThat(rpcCalls).isZero();
        assertThat(tasks(true)).isZero();
        jdbc.update(
                "UPDATE orders.checkout_attempt SET lease_until = now() - interval '1 second', recover_after = now() - interval '1 second' WHERE order_id = ?",
                order);
        worker().run();
        journal.finish(old, false);
        assertThat(status(order)).isEqualTo("RECOVERED");
        assertThat(tasks(true)).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM orders.checkout_attempt WHERE order_id = ?",
                                Integer.class,
                                order))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_error FROM orders.checkout_attempt WHERE order_id = ?",
                                String.class,
                                order))
                .isNull();
    }

    @Test
    void invalidProjectionLeavesDurableWorkUntouchedAndReplacementAllowsTheNextTick()
            throws Exception {
        UUID order = failedCheckout(true);
        Files.writeString(token, "invalid\nAuthorization: injected");
        worker().run();
        assertThat(rpcCalls).isZero();
        assertThat(status(order)).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM orders.checkout_attempt WHERE order_id = ?",
                                Integer.class,
                                order))
                .isZero();
        assertThat(counter("order.recovery.worker.failures")).isEqualTo(1);
        Files.writeString(token, TOKEN);
        worker().run();
        assertThat(status(order)).isEqualTo("RECOVERED");
        assertThat(tasks(true)).isEqualTo(2);
        assertThat(counter("order.recovery.scans")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PLACED", "CONFIRMED", "FULFILLING", "SHIPPED", "DELIVERED"})
    void acceptedActiveOrdersAreExcludedFromDiscoveryAndQueuedCleanup(String state) {
        UUID order = resources(true);
        journal.start(order);
        context.getBean(TransactionTemplate.class)
                .executeWithoutResult(
                        ignored -> {
                            jdbc.update(
                                    "INSERT INTO orders.purchase_order VALUES (?, ?)",
                                    order,
                                    state);
                            journal.accept(order);
                        });
        cleanup.enqueue(order, "RESERVATION", UUID.fromString(reservation.getId()));
        cleanup.enqueue(order, "PAYMENT", UUID.fromString(payment.getId()));
        worker().run();
        assertThat(status(order)).isEqualTo("ACCEPTED");
        assertThat(rpcCalls).isZero();
        assertThat(tasks(false)).isEqualTo(2);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVATION_STATUS_ACTIVE);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_PENDING);
    }

    private CheckoutRecoveryJob worker() {
        return new CheckoutRecoveryJob(journal, cleanup, inventory, payments, metrics, token);
    }

    private UUID failedCheckout(boolean withPayment) {
        UUID order = resources(withPayment);
        journal.start(order);
        journal.fail(order);
        return order;
    }

    private UUID resources(boolean withPayment) {
        UUID order = UUID.randomUUID();
        reservation =
                Reservation.newBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setOrderId(order.toString())
                        .setSku("SKU-1")
                        .setWarehouseId("WH-1")
                        .setQuantity(1)
                        .setStatus(ReservationStatus.RESERVATION_STATUS_ACTIVE)
                        .build();
        if (withPayment) {
            payment =
                    Payment.newBuilder()
                            .setId(UUID.randomUUID().toString())
                            .setOrderId(order.toString())
                            .setCustomerId(UUID.randomUUID().toString())
                            .setStatus(PaymentStatus.PAYMENT_STATUS_PENDING)
                            .build();
        }
        return order;
    }

    private void dueScan(UUID order) {
        jdbc.update(
                "UPDATE orders.checkout_attempt SET recover_after = now() - interval '1 second' WHERE order_id = ?",
                order);
    }

    private String status(UUID order) {
        return jdbc.queryForObject(
                "SELECT status FROM orders.checkout_attempt WHERE order_id = ?",
                String.class,
                order);
    }

    private long tasks(boolean completed) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM orders.cleanup_task WHERE completed = ?",
                Long.class,
                completed);
    }

    private double counter(String name) {
        return metrics.get(name).counter().count();
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
            var response = FindReservationsByOrderResponse.newBuilder();
            if (reservation != null && reservation.getOrderId().equals(request.getOrderId()))
                response.addReservations(reservation);
            reply(observer, response.build());
        }

        @Override
        public void getReservation(
                GetReservationRequest request, StreamObserver<Reservation> observer) {
            assertThat(request.getReservationId()).isEqualTo(reservation.getId());
            reply(observer, reservation);
        }

        @Override
        public void releaseReservation(
                ReleaseReservationRequest request, StreamObserver<Reservation> observer) {
            assertThat(request.getReservationId()).isEqualTo(reservation.getId());
            releases++;
            reservation =
                    reservation.toBuilder()
                            .setStatus(ReservationStatus.RESERVATION_STATUS_RELEASED)
                            .build();
            if (loseReleaseReplyOnce) {
                loseReleaseReplyOnce = false;
                observer.onError(
                        Status.UNAVAILABLE
                                .withDescription("fixture lost release reply")
                                .asRuntimeException());
            } else reply(observer, reservation);
        }
    }

    private class PaymentEndpoint extends PaymentServiceGrpc.PaymentServiceImplBase {
        @Override
        public void findPaymentsByOrder(
                FindPaymentsByOrderRequest request,
                StreamObserver<FindPaymentsByOrderResponse> observer) {
            if (failPaymentDiscoveryOnce) {
                failPaymentDiscoveryOnce = false;
                observer.onError(
                        Status.UNAVAILABLE
                                .withDescription("fixture lookup interruption")
                                .asRuntimeException());
                return;
            }
            var response = FindPaymentsByOrderResponse.newBuilder();
            if (payment != null && payment.getOrderId().equals(request.getOrderId()))
                response.addPayments(payment);
            reply(observer, response.build());
        }

        @Override
        public void getPayment(GetPaymentRequest request, StreamObserver<Payment> observer) {
            assertThat(request.getPaymentId()).isEqualTo(payment.getId());
            reply(observer, payment);
        }

        @Override
        public void cancelPayment(CancelPaymentRequest request, StreamObserver<Payment> observer) {
            assertThat(request.getPaymentId()).isEqualTo(payment.getId());
            cancellations++;
            payment = payment.toBuilder().setStatus(PaymentStatus.PAYMENT_STATUS_CANCELLED).build();
            reply(observer, payment);
        }
    }
}
