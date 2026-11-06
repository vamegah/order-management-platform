package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.order.clients.dto.CustomerDto;
import com.example.omp.order.domain.*;
import com.example.omp.order.events.OrderEventPublisher;
import com.example.omp.order.repository.OrderRepository;
import com.example.omp.shared.domain.Money;
import com.example.omp.shared.events.outbox.*;
import jakarta.persistence.EntityManagerFactory;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class OrderPersistenceIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine")
                    .withUrlParam("currentSchema", "orders,public");

    @org.springframework.boot.test.context.TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = {OrderRepository.class, OutboxRepository.class})
    static class Beans {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }

        @Bean(initMethod = "migrate")
        Flyway migrations(DataSource source) {
            new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS citext");
            return Flyway.configure()
                    .dataSource(source)
                    .schemas("orders")
                    .locations("classpath:db/migration")
                    .load();
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(
                DataSource source, Flyway migrations) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(
                    "com.example.omp.order.domain", "com.example.omp.shared.events.outbox");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(
                    Map.of(
                            "hibernate.hbm2ddl.auto",
                            "validate",
                            "hibernate.default_schema",
                            "orders"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        TransactionTemplate transactions(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }

        @Bean
        JdbcTemplate jdbc(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        CheckoutJournal journal(JdbcTemplate jdbc) {
            return new CheckoutJournal(jdbc);
        }

        @Bean
        OutboxWriter writer(OutboxRepository repository) {
            return new OutboxWriter(repository);
        }

        @Bean
        OrderEventPublisher events(OutboxWriter writer) {
            return new OrderEventPublisher(writer);
        }

        @Bean
        OrderTransactionService orders(
                OrderRepository repository,
                JdbcTemplate jdbc,
                OrderEventPublisher events,
                CheckoutJournal journal) {
            return new OrderTransactionService(
                    repository, new OrderNumberGenerator(jdbc), events, journal);
        }

        @Bean
        OrderFulfillmentStore fulfillment(
                JdbcTemplate jdbc, OrderTransactionService orders, OrderEventPublisher events) {
            return new OrderFulfillmentStore(jdbc, orders, events);
        }
    }

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static OrderTransactionService orders;
    private static TransactionTemplate transactions;
    private static OrderRepository repository;
    private static OrderEventPublisher events;
    private static OrderFulfillmentStore fulfillment;

    @BeforeAll
    static void start() {
        context = new AnnotationConfigApplicationContext(Beans.class);
        jdbc = context.getBean(JdbcTemplate.class);
        orders = context.getBean(OrderTransactionService.class);
        transactions = context.getBean(TransactionTemplate.class);
        repository = context.getBean(OrderRepository.class);
        events = context.getBean(OrderEventPublisher.class);
        fulfillment = context.getBean(OrderFulfillmentStore.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    private UUID create(UUID reservation) {
        UUID id = UUID.randomUUID();
        context.getBean(CheckoutJournal.class).start(id);
        var saved =
                orders.create(
                        id,
                        new CustomerDto(
                                UUID.randomUUID(),
                                "customer@example.com",
                                "A",
                                "B",
                                "ACTIVE",
                                List.of(),
                                null),
                        new ShippingAddress("1 Main", null, "Chicago", "IL", "60601", "US"),
                        List.of(
                                new OrderTransactionService.OrderLineDraft(
                                        UUID.randomUUID(), "SKU", "Product", Money.usd("10"), 1)),
                        UUID.randomUUID(),
                        List.of(reservation));
        assertThat(saved.getId()).isEqualTo(id);
        return id;
    }

    private long eventCount(UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM orders.outbox_event WHERE aggregate_id = ?",
                Long.class,
                id.toString());
    }

    @Test
    void acceptancePreservesCheckoutIdAndDurableReservationAndLoadsBothCollections() {
        UUID reservation = UUID.randomUUID();
        UUID id = create(reservation);
        var saved = orders.findByIdFull(id);
        assertThat(saved.getLines())
                .singleElement()
                .satisfies(line -> assertThat(line.getReservationId()).isEqualTo(reservation));
        assertThat(saved.getStatusHistory()).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM orders.checkout_attempt WHERE order_id = ?",
                                String.class,
                                id))
                .isEqualTo("ACCEPTED");
        assertThat(eventCount(id)).isEqualTo(1);
    }

    @Test
    void concurrentTransitionsCannotBothCommitHistoryAndOutboxEvents() throws Exception {
        UUID id = create(UUID.randomUUID());
        var loaded = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Void> mutation =
                    () ->
                            transactions.execute(
                                    status -> {
                                        var order =
                                                repository
                                                        .findByIdWithFullDetails(id)
                                                        .orElseThrow();
                                        loaded.countDown();
                                        try {
                                            if (!release.await(5, TimeUnit.SECONDS))
                                                throw new AssertionError("Start barrier timed out");
                                        } catch (InterruptedException failure) {
                                            Thread.currentThread().interrupt();
                                            throw new IllegalStateException(failure);
                                        }
                                        order.place("customer");
                                        events.publishPlaced(order, "customer");
                                        return null;
                                    });
            var first = executor.submit(mutation);
            var second = executor.submit(mutation);
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            int succeeded = 0, conflicts = 0;
            for (var result : List.of(first, second)) {
                try {
                    result.get(15, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException failure) {
                    assertThat(failure.getCause())
                            .isInstanceOf(
                                    org.springframework.dao.OptimisticLockingFailureException
                                            .class);
                    conflicts++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        }
        assertThat(orders.findByIdFull(id).getStatusHistory()).hasSize(2);
        assertThat(eventCount(id)).isEqualTo(2);
    }

    @Test
    void rolledBackTransitionLeavesNoHistoryOrOutboxEvent() {
        UUID id = create(UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                transactions.execute(
                                        status -> {
                                            var order =
                                                    repository
                                                            .findByIdWithFullDetails(id)
                                                            .orElseThrow();
                                            order.place("customer");
                                            events.publishPlaced(order, "customer");
                                            throw new IllegalStateException("Injected failure");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        var saved = orders.findByIdFull(id);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(saved.getStatusHistory()).hasSize(1);
        assertThat(eventCount(id)).isEqualTo(1);
    }

    private UUID shipmentIntent() {
        UUID id = create(UUID.randomUUID());
        orders.findAndApply(
                id,
                order -> {
                    order.place("customer");
                    order.confirm("operator", "Paid");
                    order.requestShipment("operator", "Dispatch");
                    fulfillment.enqueue(order, "operator", "Dispatch");
                });
        return id;
    }

    private com.example.omp.order.clients.grpc.dto.PaymentIntentDto payment(UUID id) {
        var order = orders.findByIdFull(id);
        return new com.example.omp.order.clients.grpc.dto.PaymentIntentDto(
                order.getPaymentId(),
                id,
                order.getCustomerId(),
                order.getTotal().getAmount().toPlainString(),
                "USD",
                "PAYMENT_STATUS_CONFIRMED",
                "pi-confirmed",
                0,
                0);
    }

    @Test
    void shipmentIntentAndTasksRollbackWithTheAggregate() {
        UUID id = create(UUID.randomUUID());
        orders.findAndApply(
                id,
                order -> {
                    order.place("customer");
                    order.confirm("operator", "Paid");
                });
        assertThatThrownBy(
                        () ->
                                transactions.execute(
                                        status -> {
                                            orders.findAndApply(
                                                    id,
                                                    order -> {
                                                        order.requestShipment(
                                                                "operator", "Dispatch");
                                                        fulfillment.enqueue(
                                                                order, "operator", "Dispatch");
                                                    });
                                            throw new IllegalStateException(
                                                    "Injected transaction failure");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(orders.findByIdFull(id).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.fulfillment_request WHERE order_id = ?",
                                Long.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.fulfillment_task WHERE order_id = ?",
                                Long.class,
                                id))
                .isZero();
    }

    @Test
    void expiredFulfillmentLeaseRejectsStaleAcknowledgementAndFinalizesOnlyOnce() {
        UUID id = shipmentIntent();
        assertThat(fulfillment.startAttempt(id)).isTrue();
        var original = fulfillment.claimTask(id).orElseThrow();
        assertThat(fulfillment.claimTask(id)).isEmpty();
        assertThat(fulfillment.finish(id, payment(id))).isFalse();
        jdbc.update(
                "UPDATE orders.fulfillment_task SET lease_until = now() - interval '1 minute' WHERE id = ?",
                original.id());
        var replacement = fulfillment.claimTask(id).orElseThrow();
        assertThat(replacement.leaseId()).isNotEqualTo(original.leaseId());
        assertThat(fulfillment.completeTask(original)).isFalse();
        assertThat(fulfillment.completeTask(replacement)).isTrue();
        long before = eventCount(id);
        assertThat(fulfillment.finish(id, payment(id))).isTrue();
        assertThat(fulfillment.finish(id, payment(id))).isFalse();
        assertThat(orders.findByIdFull(id).getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(eventCount(id)).isEqualTo(before + 1);
        assertThat(fulfillment.pendingCount()).isGreaterThanOrEqualTo(0);
        assertThat(fulfillment.startAttempt(id)).isFalse();
    }

    @Test
    void shipmentCompletionRollbackKeepsAcknowledgedTasksForSafeRetry() {
        UUID id = shipmentIntent();
        var task = fulfillment.claimTask(id).orElseThrow();
        assertThat(fulfillment.completeTask(task)).isTrue();
        long before = eventCount(id);
        assertThatThrownBy(
                        () ->
                                transactions.execute(
                                        status -> {
                                            assertThat(fulfillment.finish(id, payment(id)))
                                                    .isTrue();
                                            throw new IllegalStateException(
                                                    "Injected completion failure");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(orders.findByIdFull(id).getStatus()).isEqualTo(OrderStatus.FULFILLING);
        assertThat(fulfillment.readyForCompletion(id)).isTrue();
        assertThat(eventCount(id)).isEqualTo(before);
        assertThat(fulfillment.finish(id, payment(id))).isTrue();
        assertThat(eventCount(id)).isEqualTo(before + 1);
    }

    @Test
    void actualStoreTransactionsEndBeforeInventoryAndPaymentCalls() {
        UUID id = shipmentIntent();
        var order = orders.findByIdFull(id);
        var inventory =
                org.mockito.Mockito.mock(com.example.omp.order.clients.grpc.InventoryClient.class);
        var payments =
                org.mockito.Mockito.mock(com.example.omp.order.clients.grpc.PaymentClient.class);
        var line = order.getLines().getFirst();
        org.mockito.Mockito.when(inventory.get(line.getReservationId()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return new com.example.omp.order.clients.grpc.dto.ReservationDto(
                                    line.getReservationId(),
                                    line.getProductSku(),
                                    "WH-1",
                                    line.getQuantity(),
                                    id,
                                    "RESERVATION_STATUS_COMMITTED",
                                    0,
                                    0);
                        });
        org.mockito.Mockito.when(payments.get(order.getPaymentId()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return payment(id);
                        });
        var queue =
                new OrderFulfillmentQueue(
                        fulfillment,
                        inventory,
                        payments,
                        orders,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        assertThat(queue.retryOrder(id, 100)).isEqualTo(1);
        assertThat(orders.findByIdFull(id).getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void partialReservationAcknowledgementCannotCompleteAMultiLineShipment() {
        UUID id = create(UUID.randomUUID());
        orders.findAndApply(
                id,
                order -> {
                    var second =
                            new OrderLine(UUID.randomUUID(), "SKU-2", "Second", Money.usd("10"), 1);
                    second.setReservationId(UUID.randomUUID());
                    order.addLine(second);
                    order.place("customer");
                    order.confirm("operator", "Paid");
                    order.requestShipment("operator", "Dispatch");
                    fulfillment.enqueue(order, "operator", "Dispatch");
                });
        var first = fulfillment.claimTask(id).orElseThrow();
        var second = fulfillment.claimTask(id).orElseThrow();
        assertThat(first.reservationId()).isNotEqualTo(second.reservationId());
        assertThat(fulfillment.completeTask(first)).isTrue();
        assertThat(fulfillment.readyForCompletion(id)).isFalse();
        assertThat(fulfillment.finish(id, payment(id))).isFalse();
        assertThat(orders.findByIdFull(id).getStatus()).isEqualTo(OrderStatus.FULFILLING);
        assertThat(fulfillment.completeTask(second)).isTrue();
        assertThat(fulfillment.readyForCompletion(id)).isTrue();
        assertThat(fulfillment.finish(id, payment(id))).isTrue();
        assertThat(orders.findByIdFull(id).getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }
}
