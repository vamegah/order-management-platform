package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.order.dto.RecoveryAdoptionRequest;
import com.example.omp.order.error.InvalidOrderStateException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

        @Bean
        CheckoutRecoveryAdoption adoption(JdbcTemplate jdbc) {
            return new CheckoutRecoveryAdoption(jdbc);
        }
    }

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CheckoutJournal journal;
    private static TransactionTemplate transactions;
    private static CheckoutRecoveryAdoption adoption;

    @BeforeAll
    static void initialize() throws Exception {
        context = new AnnotationConfigApplicationContext(Beans.class);
        jdbc = context.getBean(JdbcTemplate.class);
        journal = context.getBean(CheckoutJournal.class);
        transactions = context.getBean(TransactionTemplate.class);
        adoption = context.getBean(CheckoutRecoveryAdoption.class);
        jdbc.execute("CREATE SCHEMA orders");
        jdbc.execute(
                "CREATE TABLE orders.purchase_order (id uuid PRIMARY KEY, status varchar(20) NOT NULL)");
        jdbc.execute(
                new ClassPathResource("db/migration/V8__checkout_journal.sql")
                        .getContentAsString(StandardCharsets.UTF_8));
        jdbc.execute(
                new ClassPathResource("db/migration/V12__checkout_recovery_adoption.sql")
                        .getContentAsString(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void reset() {
        jdbc.execute(
                "TRUNCATE orders.checkout_recovery_adoption, orders.checkout_attempt, orders.purchase_order");
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
    void auditedOrphanAdmissionBecomesDiscoverableAndRetriesPreserveWorkerState() {
        UUID order = UUID.randomUUID();
        var request =
                new RecoveryAdoptionRequest("  Verified historical orphan; incident INC-123  ");
        var first = adoption.adopt(order, request, "first-operator");
        assertThat(first.submitted()).isTrue();
        assertThat(first.status()).isEqualTo("FAILED");
        assertThat(first.reason()).isEqualTo(request.reason().strip());
        assertThat(first.requestedAt()).isNotNull();
        var claim = journal.claim(1).getFirst();
        assertThat(claim.orderId()).isEqualTo(order);
        var retry =
                adoption.adopt(
                        order, new RecoveryAdoptionRequest("Repeated request"), "second-operator");
        assertThat(retry.submitted()).isFalse();
        assertThat(retry.status()).isEqualTo("RECOVERING");
        assertThat(retry.requestedBy()).isEqualTo("first-operator");
        assertThat(retry.reason()).isEqualTo(first.reason());
        assertThat(retry.requestedAt()).isEqualTo(first.requestedAt());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT lease_id FROM orders.checkout_attempt WHERE order_id = ?",
                                UUID.class,
                                order))
                .isEqualTo(claim.leaseId());
        assertThat(journal.claim(1)).isEmpty();
        journal.finish(claim, true);
        assertThat(adoption.adopt(order, request, "first-operator").status())
                .isEqualTo("RECOVERED");
        assertThat(journal.claim(1)).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.checkout_recovery_adoption WHERE order_id = ?",
                                Long.class,
                                order))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                transactions.execute(
                                        ignored -> {
                                            journal.accept(order);
                                            return null;
                                        }))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DRAFT",
                "PLACED",
                "CONFIRMED",
                "FULFILLING",
                "SHIPPED",
                "DELIVERED",
                "CANCELLED"
            })
    void everyExistingAggregateIsExcludedFromOrphanAdmission(String state) {
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO orders.purchase_order VALUES (?, ?)", order, state);
        assertThatThrownBy(
                        () ->
                                adoption.adopt(
                                        order,
                                        new RecoveryAdoptionRequest("Reviewed orphan"),
                                        "operator"))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.checkout_attempt WHERE order_id = ?",
                                Long.class,
                                order))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.checkout_recovery_adoption",
                                Long.class))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"STARTED", "FAILED", "RECOVERING", "RECOVERED", "ACCEPTED"})
    void normalCheckoutAttemptsCannotBeAdoptedOrHaveTheirLeaseReset(String state) {
        UUID order = UUID.randomUUID(), lease = UUID.randomUUID();
        journal.start(order);
        jdbc.update(
                "UPDATE orders.checkout_attempt SET status = ?, lease_id = ?, lease_until = now() + interval '2 minutes' WHERE order_id = ?",
                state,
                lease,
                order);
        var before =
                jdbc.queryForMap("SELECT * FROM orders.checkout_attempt WHERE order_id = ?", order);
        assertThatThrownBy(
                        () ->
                                adoption.adopt(
                                        order,
                                        new RecoveryAdoptionRequest("Reviewed orphan"),
                                        "operator"))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM orders.checkout_attempt WHERE order_id = ?", order))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.checkout_recovery_adoption",
                                Long.class))
                .isZero();
    }

    @Test
    void auditFailureRollsBackAdmissionAndCanRetryWithoutAnUntrackedAttempt() {
        UUID order = UUID.randomUUID();
        var request = new RecoveryAdoptionRequest("Reviewed orphan");
        jdbc.execute(
                "ALTER TABLE orders.checkout_recovery_adoption ADD CONSTRAINT injected_adoption_failure CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(() -> adoption.adopt(order, request, "operator"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.execute(
                    "ALTER TABLE orders.checkout_recovery_adoption DROP CONSTRAINT injected_adoption_failure");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.checkout_attempt WHERE order_id = ?",
                                Long.class,
                                order))
                .isZero();
        assertThat(adoption.adopt(order, request, "operator").submitted()).isTrue();
    }

    @Test
    void competingOperatorsCreateOnlyOneAdmissionAndPreserveItsFirstAudit() throws Exception {
        UUID order = UUID.randomUUID();
        var ready = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<com.example.omp.order.dto.RecoveryAdoptionResult> action =
                    () -> {
                        ready.countDown();
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                        return adoption.adopt(
                                order, new RecoveryAdoptionRequest("Reviewed orphan"), "operator");
                    };
            var first = executor.submit(action);
            var second = executor.submit(action);
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            assertThat(
                            java.util.List.of(
                                    first.get(15, TimeUnit.SECONDS).submitted(),
                                    second.get(15, TimeUnit.SECONDS).submitted()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders.checkout_recovery_adoption WHERE order_id = ?",
                                Long.class,
                                order))
                .isEqualTo(1);
    }

    @Test
    void normalStartAndManualAdmissionCannotBothClaimTheSameJournalIdentity() throws Exception {
        UUID order = UUID.randomUUID();
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var started =
                    executor.submit(
                            () -> {
                                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                                try {
                                    journal.start(order);
                                    return true;
                                } catch (
                                        org.springframework.dao.DataIntegrityViolationException
                                                expected) {
                                    return false;
                                }
                            });
            var adopted =
                    executor.submit(
                            () -> {
                                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                                try {
                                    return adoption.adopt(
                                                    order,
                                                    new RecoveryAdoptionRequest("Reviewed orphan"),
                                                    "operator")
                                            .submitted();
                                } catch (InvalidOrderStateException expected) {
                                    return false;
                                }
                            });
            release.countDown();
            boolean normalWon = started.get(15, TimeUnit.SECONDS);
            assertThat(adopted.get(15, TimeUnit.SECONDS)).isEqualTo(!normalWon);
            assertThat(status(order)).isEqualTo(normalWon ? "STARTED" : "FAILED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM orders.checkout_recovery_adoption WHERE order_id = ?",
                                    Long.class,
                                    order))
                    .isEqualTo(normalWon ? 0L : 1L);
        }
    }

    @Test
    void recoveryCannotObserveAdmissionBeforeItsAuditTransactionCommits() {
        UUID order = UUID.randomUUID();
        try (var executor = Executors.newSingleThreadExecutor()) {
            transactions.execute(
                    ignored -> {
                        adoption.adopt(
                                order, new RecoveryAdoptionRequest("Reviewed orphan"), "operator");
                        try {
                            assertThat(
                                            executor.submit(() -> journal.claim(1))
                                                    .get(5, TimeUnit.SECONDS))
                                    .isEmpty();
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                        return null;
                    });
        }
        assertThat(journal.claim(1))
                .extracting(CheckoutJournal.Attempt::orderId)
                .containsExactly(order);
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
