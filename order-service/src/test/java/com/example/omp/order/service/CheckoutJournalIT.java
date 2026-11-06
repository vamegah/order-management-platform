package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Database fencing tests; no remote services or mocked database behavior. */
@Testcontainers
class CheckoutJournalIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine");

    @org.springframework.boot.test.context.TestConfiguration
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
    }

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CheckoutJournal journal;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void initialize() throws Exception {
        context = new AnnotationConfigApplicationContext(Beans.class);
        jdbc = context.getBean(JdbcTemplate.class);
        journal = context.getBean(CheckoutJournal.class);
        transactions = context.getBean(TransactionTemplate.class);
        jdbc.execute("CREATE SCHEMA orders");
        jdbc.execute(
                "CREATE TABLE orders.purchase_order (id uuid PRIMARY KEY, status varchar(20) NOT NULL)");
        jdbc.execute(
                new ClassPathResource("db/migration/V8__checkout_journal.sql")
                        .getContentAsString(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE orders.checkout_attempt, orders.purchase_order");
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
    }

    private UUID staleAttempt() {
        UUID order = UUID.randomUUID();
        journal.start(order);
        due(order);
        return order;
    }

    private void due(UUID order) {
        jdbc.update(
                "UPDATE orders.checkout_attempt SET recover_after = now() - interval '1 minute' WHERE order_id = ?",
                order);
    }

    private String status(UUID order) {
        return jdbc.queryForObject(
                "SELECT status FROM orders.checkout_attempt WHERE order_id = ?",
                String.class,
                order);
    }

    @Test
    void recoveryFencesLateAcceptance() {
        UUID order = staleAttempt();
        assertThat(journal.claim(1))
                .extracting(CheckoutJournal.Attempt::orderId)
                .containsExactly(order);
        assertThatThrownBy(
                        () ->
                                transactions.execute(
                                        ignored -> {
                                            journal.accept(order);
                                            return null;
                                        }))
                .isInstanceOf(com.example.omp.order.error.InvalidOrderStateException.class);
        assertThat(status(order)).isEqualTo("RECOVERING");
    }

    @Test
    void acceptanceAndOrderInsertRollbackTogether() {
        UUID order = staleAttempt();
        assertThatThrownBy(
                        () ->
                                transactions.execute(
                                        ignored -> {
                                            journal.accept(order);
                                            jdbc.update(
                                                    "INSERT INTO orders.purchase_order VALUES (?, 'DRAFT')",
                                                    order);
                                            throw new IllegalStateException(
                                                    "Injected commit failure");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(status(order)).isEqualTo("STARTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders.purchase_order", Long.class))
                .isZero();
    }

    @Test
    void recoverySkipsUncommittedAcceptanceAndAcceptedAttempts() {
        UUID order = staleAttempt();
        try (var executor = Executors.newSingleThreadExecutor()) {
            transactions.execute(
                    ignored -> {
                        journal.accept(order);
                        try {
                            assertThat(
                                            executor.submit(() -> journal.claim(1))
                                                    .get(3, TimeUnit.SECONDS))
                                    .isEmpty();
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                        return null;
                    });
        }
        assertThat(journal.claim(1)).isEmpty();
        assertThat(status(order)).isEqualTo("ACCEPTED");
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldWorkerCannotAcknowledgeIt() {
        UUID order = staleAttempt();
        var first = journal.claim(1).getFirst();
        assertThat(journal.claim(1)).isEmpty();
        jdbc.update(
                "UPDATE orders.checkout_attempt SET lease_until = now() - interval '1 minute' WHERE order_id = ?",
                order);
        var replacement = journal.claim(1).getFirst();
        assertThat(replacement.leaseId()).isNotEqualTo(first.leaseId());
        journal.finish(first, true);
        assertThat(status(order)).isEqualTo("RECOVERING");
        journal.finish(replacement, true);
        assertThat(status(order)).isEqualTo("RECOVERED");
    }

    @Test
    void recoveredAttemptsRemainDiscoverableForLateRemoteCommits() {
        UUID order = staleAttempt();
        journal.finish(journal.claim(1).getFirst(), true);
        due(order);
        assertThat(journal.claim(1))
                .extracting(CheckoutJournal.Attempt::orderId)
                .containsExactly(order);
    }
}
