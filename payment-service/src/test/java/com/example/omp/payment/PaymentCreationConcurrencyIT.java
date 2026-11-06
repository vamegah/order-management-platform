package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.payment.domain.MoneyConverter;
import com.example.omp.payment.events.PaymentEventPublisher;
import com.example.omp.payment.grpc.handler.PaymentIntentHandler;
import com.example.omp.payment.repository.PaymentRepository;
import com.example.omp.payment.service.*;
import com.example.omp.proto.payment.CreatePaymentIntentRequest;
import com.example.omp.shared.events.EventSerde;
import com.example.omp.shared.events.outbox.OutboxRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real PostgreSQL/JPA cross-protocol creation and atomic outbox tests. */
@Testcontainers
class PaymentCreationConcurrencyIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.4-alpine")
                    .withUrlParam("currentSchema", "payments,public");

    @org.springframework.boot.test.context.TestConfiguration
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = {PaymentRepository.class, OutboxRepository.class})
    static class Beans {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }

        @Bean(initMethod = "migrate")
        Flyway migrations(DataSource source) {
            return Flyway.configure()
                    .dataSource(source)
                    .schemas("payments")
                    .locations("classpath:db/migration")
                    .load();
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(
                DataSource source, Flyway migrations) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(
                    "com.example.omp.payment.domain", "com.example.omp.shared.events.outbox");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(
                    Map.of(
                            "hibernate.hbm2ddl.auto",
                            "validate",
                            "hibernate.default_schema",
                            "payments"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        JdbcTemplate jdbc(DataSource source) {
            return new JdbcTemplate(source);
        }

        @Bean
        PaymentCreationLock lock(JdbcTemplate jdbc) {
            return new PaymentCreationLock(jdbc);
        }

        @Bean
        com.example.omp.shared.events.outbox.OutboxWriter sharedWriter(
                OutboxRepository repository) {
            return new com.example.omp.shared.events.outbox.OutboxWriter(repository);
        }

        @Bean
        PaymentEventPublisher events(com.example.omp.shared.events.outbox.OutboxWriter writer) {
            return new PaymentEventPublisher(writer);
        }

        @Bean
        com.example.omp.payment.outbox.OutboxWriter restWriter(OutboxRepository repository) {
            return new com.example.omp.payment.outbox.OutboxWriter(
                    repository, EventSerde.buildObjectMapper());
        }

        @Bean
        PaymentIntentHandler grpc(
                PaymentRepository repository,
                PaymentEventPublisher events,
                PaymentCreationLock lock) {
            return new PaymentIntentHandler(repository, new MoneyConverter(), events, lock);
        }

        @Bean
        PaymentTransactionalOps rest(
                PaymentRepository repository,
                com.example.omp.payment.outbox.OutboxWriter writer,
                PaymentCreationLock lock) {
            return new PaymentTransactionalOps(repository, writer, lock);
        }

        @Bean
        RefundStore refunds(
                PaymentRepository payments,
                com.example.omp.payment.repository.RefundRepository refunds,
                PaymentEventPublisher events,
                PaymentCreationLock keys) {
            return new RefundStore(payments, refunds, events, keys);
        }

        @Bean
        com.example.omp.payment.provider.PaymentProvider provider() {
            return org.mockito.Mockito.mock(com.example.omp.payment.provider.PaymentProvider.class);
        }

        @Bean
        RefundApplicationService refundService(
                RefundStore store, com.example.omp.payment.provider.PaymentProvider provider) {
            return new RefundApplicationService(store, provider);
        }

        @Bean
        RefundRecoveryService recovery(
                RefundStore store, com.example.omp.payment.provider.PaymentProvider provider) {
            return new RefundRecoveryService(
                    store,
                    provider,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        }

        @Bean
        RefundReconciliationStore reconciliationStore(
                PaymentRepository payments,
                com.example.omp.payment.repository.RefundRepository refunds,
                com.example.omp.payment.repository.RefundReconciliationAuditRepository audits,
                RefundStore state) {
            return new RefundReconciliationStore(payments, refunds, audits, state);
        }

        @Bean
        RefundReconciliationService reconciliationService(
                RefundReconciliationStore store,
                com.example.omp.payment.provider.PaymentProvider provider) {
            return new RefundReconciliationService(
                    store,
                    provider,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        }

        @Bean
        PaymentProviderEventService providerEvents(
                com.example.omp.payment.repository.PaymentProviderEventRepository receipts,
                PaymentRepository payments,
                com.example.omp.payment.outbox.OutboxWriter writer,
                RefundStore refunds) {
            return new PaymentProviderEventService(receipts, payments, writer, refunds);
        }
    }

    private static AnnotationConfigApplicationContext context;
    private static PaymentIntentHandler grpc;
    private static PaymentTransactionalOps rest;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new org.springframework.core.env.MapPropertySource(
                                "payment-test", Map.of("payment.provider", "stripe")));
        context.register(Beans.class);
        context.refresh();
        grpc = context.getBean(PaymentIntentHandler.class);
        rest = context.getBean(PaymentTransactionalOps.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    private CreatePaymentIntentRequest request(UUID order, UUID customer, String key) {
        return CreatePaymentIntentRequest.newBuilder()
                .setOrderId(order.toString())
                .setCustomerId(customer.toString())
                .setIdempotencyKey(key)
                .setAmount(
                        com.example.omp.proto.common.Money.newBuilder()
                                .setAmount("10.00")
                                .setCurrency("USD"))
                .build();
    }

    private void singleCreation(String key, UUID id) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments.payment WHERE idempotency_key = ?",
                                Long.class,
                                key))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments.outbox_event WHERE aggregate_id = ?",
                                Long.class,
                                id.toString()))
                .isEqualTo(1);
    }

    @Test
    void simultaneousGrpcRetriesCommitOnePaymentAndEvent() throws Exception {
        String key = UUID.randomUUID().toString();
        var request = request(UUID.randomUUID(), UUID.randomUUID(), key);
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> create =
                    () -> {
                        ready.countDown();
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("Start barrier timed out");
                        return grpc.create(request).getId();
                    };
            var first = executor.submit(create);
            var second = executor.submit(create);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            String id = first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(id);
            singleCreation(key, UUID.fromString(id));
        }
    }

    @Test
    void restAndGrpcShareTheSameCreationLock() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID order = UUID.randomUUID(), customer = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("Start barrier timed out");
                                return UUID.fromString(
                                        grpc.create(request(order, customer, key)).getId());
                            });
            var second =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("Start barrier timed out");
                                return rest.createOrFindPending(
                                                new PaymentApplicationService.ChargeCommand(
                                                        order, customer, 1000, "USD", key),
                                                "stripe")
                                        .getId();
                            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            UUID id = first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(id);
            singleCreation(key, id);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT provider FROM payments.payment WHERE id = ?",
                                    String.class,
                                    id))
                    .isEqualTo("stripe");
        }
    }

    @Test
    void mismatchedReplayRollsBackWithoutAnAdditionalEvent() {
        String key = UUID.randomUUID().toString();
        UUID order = UUID.randomUUID(), customer = UUID.randomUUID();
        UUID id = UUID.fromString(grpc.create(request(order, customer, key)).getId());
        assertThatThrownBy(
                        () ->
                                rest.createOrFindPending(
                                        new PaymentApplicationService.ChargeCommand(
                                                order, customer, 2000, "USD", key),
                                        "stripe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                rest.createOrFindPending(
                                        new PaymentApplicationService.ChargeCommand(
                                                order, customer, 1000, "USD", key),
                                        "noop"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different provider");
        singleCreation(key, id);
    }

    private UUID confirmedPayment() {
        UUID id =
                rest.createOrFindPending(
                                new PaymentApplicationService.ChargeCommand(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        1000,
                                        "USD",
                                        UUID.randomUUID().toString()),
                                "stripe")
                        .getId();
        jdbc.update(
                "UPDATE payments.payment SET status = 'CONFIRMED', provider_reference = ? WHERE id = ?",
                "pi-" + id,
                id);
        return id;
    }

    @Test
    void refundIntentIsCommittedBeforeProviderIoAndSurvivesLostReply() {
        UUID id = confirmedPayment();
        var provider = context.getBean(com.example.omp.payment.provider.PaymentProvider.class);
        org.mockito.Mockito.reset(provider);
        org.mockito.Mockito.when(provider.providerName()).thenReturn("stripe");
        org.mockito.Mockito.when(provider.refund(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM payments.refund WHERE payment_id = ?",
                                                    Long.class,
                                                    id))
                                    .isEqualTo(1);
                            throw new IllegalStateException("lost reply");
                        })
                .thenReturn(
                        com.example.omp.payment.provider.ProviderRefundResult.succeeded(
                                "re-" + id));
        var service = context.getBean(RefundApplicationService.class);
        String key = UUID.randomUUID().toString();
        var first = service.refund(id, 400, null, key);
        assertThat(first.getStatus())
                .isEqualTo(com.example.omp.payment.domain.RefundStatus.PENDING);
        var second = service.refund(id, 400, null, key);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getStatus())
                .isEqualTo(com.example.omp.payment.domain.RefundStatus.COMPLETED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT refunded_amount FROM payments.payment WHERE id = ?",
                                java.math.BigDecimal.class,
                                id))
                .isEqualByComparingTo("4");
    }

    @Test
    void competingKeysCannotAdmitTwoPendingRefunds() throws Exception {
        UUID id = confirmedPayment();
        var store = context.getBean(RefundStore.class);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> prepare =
                    () -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            store.prepare(
                                    id, 600L, null, null, UUID.randomUUID().toString(), "stripe");
                            return true;
                        } catch (IllegalStateException blocked) {
                            return false;
                        }
                    };
            var first = executor.submit(prepare);
            var second = executor.submit(prepare);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments.refund WHERE payment_id = ?",
                                Long.class,
                                id))
                .isEqualTo(1);
    }

    @Test
    void completionAndOutboxRollbackTogetherThenRetryOnce() {
        UUID id = confirmedPayment();
        var store = context.getBean(RefundStore.class);
        var refund = store.prepare(id, 400L, null, null, UUID.randomUUID().toString(), "stripe");
        var attempt = store.claim(id, refund.getId());
        var result = com.example.omp.payment.provider.ProviderRefundResult.succeeded("re-" + id);
        var transactions =
                new org.springframework.transaction.support.TransactionTemplate(
                        context.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        status -> {
                                            store.complete(attempt, result);
                                            throw new IllegalStateException("rollback");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.read(id, refund.getId()).getStatus())
                .isEqualTo(com.example.omp.payment.domain.RefundStatus.PENDING);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments.outbox_event WHERE aggregate_id = ?",
                                Long.class,
                                id.toString()))
                .isEqualTo(1);
        store.complete(attempt, result);
        store.complete(attempt, result);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments.outbox_event WHERE aggregate_id = ?",
                                Long.class,
                                id.toString()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT refunded_amount FROM payments.payment WHERE id = ?",
                                java.math.BigDecimal.class,
                                id))
                .isEqualByComparingTo("4");
    }

    @Test
    void earlyWebhookReceiptAndRefundCompletionCommitOnce() {
        UUID id = confirmedPayment();
        var store = context.getBean(RefundStore.class);
        var refund = store.prepare(id, 400L, null, null, UUID.randomUUID().toString(), "stripe");
        var attempt = store.claim(id, refund.getId());
        assertThat(attempt.request().idempotencyKey()).isEqualTo("omp-refund-" + refund.getId());
        var data = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        data.put("id", "re-" + id)
                .put("amount", 400)
                .put("currency", "usd")
                .put("status", "succeeded");
        data.putObject("metadata").put("refund_id", refund.getId().toString());
        var event =
                new com.example.omp.payment.provider.ProviderWebhookEvent(
                        "evt-" + id, "refund.updated", data, "pi-" + id);
        var processor = context.getBean(PaymentProviderEventService.class);
        assertThat(processor.process("stripe", event))
                .isEqualTo(PaymentProviderEventService.ProcessResult.ACCEPTED);
        assertThat(processor.process("stripe", event))
                .isEqualTo(PaymentProviderEventService.ProcessResult.DUPLICATE);
        store.complete(
                attempt,
                com.example.omp.payment.provider.ProviderRefundResult.succeeded("re-" + id));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments.outbox_event WHERE aggregate_id = ?",
                                Long.class,
                                id.toString()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM payments.provider_event WHERE provider_event_id = ?",
                                String.class,
                                "evt-" + id))
                .isEqualTo("PROCESSED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT refunded_amount FROM payments.payment WHERE id = ?",
                                java.math.BigDecimal.class,
                                id))
                .isEqualByComparingTo("4");
    }

    @Test
    void recoveryReadsKnownRefundOutsideTransactionAndCommitsMetadata() {
        UUID id = confirmedPayment();
        var store = context.getBean(RefundStore.class);
        var refund = store.prepare(id, 400L, null, null, UUID.randomUUID().toString(), "stripe");
        String reference = "re_" + id.toString().replace("-", "");
        jdbc.update(
                "UPDATE payments.refund SET provider_reference = ?, created_at = now() - interval '21 hours' WHERE id = ?",
                reference,
                refund.getId());
        var provider = context.getBean(com.example.omp.payment.provider.PaymentProvider.class);
        org.mockito.Mockito.reset(provider);
        org.mockito.Mockito.when(provider.providerName()).thenReturn("stripe");
        org.mockito.Mockito.when(
                        provider.lookupRefund(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.eq(reference)))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT recovery_attempts FROM payments.refund WHERE id = ?",
                                                    Integer.class,
                                                    refund.getId()))
                                    .isEqualTo(1);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT attempt_id IS NOT NULL FROM payments.refund WHERE id = ?",
                                                    Boolean.class,
                                                    refund.getId()))
                                    .isTrue();
                            return com.example.omp.payment.provider.ProviderRefundResult.succeeded(
                                    reference);
                        });
        var recovery = context.getBean(RefundRecoveryService.class);
        assertThat(recovery.retry(id, refund.getId()).getStatus())
                .isEqualTo(com.example.omp.payment.domain.RefundStatus.COMPLETED);
        recovery.retry(id, refund.getId());
        org.mockito.Mockito.verify(provider)
                .lookupRefund(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(reference));
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.never())
                .refund(org.mockito.ArgumentMatchers.any());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT refunded_amount FROM payments.payment WHERE id = ?",
                                java.math.BigDecimal.class,
                                id))
                .isEqualByComparingTo("4");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments.outbox_event WHERE aggregate_id = ?",
                                Long.class,
                                id.toString()))
                .isEqualTo(2);
    }

    @Test
    void recoverySkipsPaymentLockedByAnotherTransaction() throws Exception {
        UUID id = confirmedPayment();
        var store = context.getBean(RefundStore.class);
        var refund = store.prepare(id, 400L, null, null, UUID.randomUUID().toString(), "stripe");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var transactions =
                new org.springframework.transaction.support.TransactionTemplate(
                        context.getBean(PlatformTransactionManager.class));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder =
                    executor.submit(
                            () ->
                                    transactions.executeWithoutResult(
                                            status -> {
                                                context.getBean(PaymentRepository.class)
                                                        .lockForRefund(id)
                                                        .orElseThrow();
                                                locked.countDown();
                                                try {
                                                    if (!release.await(5, TimeUnit.SECONDS))
                                                        throw new AssertionError(
                                                                "Lock release timed out");
                                                } catch (InterruptedException interrupted) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(interrupted);
                                                }
                                            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(store.claimRecovery(id, refund.getId(), "stripe", false)).isNull();
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(store.claimRecovery(id, refund.getId(), "stripe", false)).isNotNull();
    }

    @Test
    void latePendingWebhookUnblocksExpiredIntentForReadOnlyRecovery() {
        UUID id = confirmedPayment();
        var store = context.getBean(RefundStore.class);
        var refund = store.prepare(id, 400L, null, null, UUID.randomUUID().toString(), "stripe");
        jdbc.update(
                "UPDATE payments.refund SET created_at = now() - interval '21 hours' WHERE id = ?",
                refund.getId());
        assertThat(store.claimRecovery(id, refund.getId(), "stripe", true)).isNull();
        assertThat(store.read(id, refund.getId()).getAttentionReason())
                .isEqualTo("REPLAY_WINDOW_EXPIRED");
        assertThat(store.dueRecovery())
                .noneMatch(candidate -> candidate.refundId().equals(refund.getId()));
        var data = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        data.put("id", "re_late")
                .put("amount", 400)
                .put("currency", "usd")
                .put("status", "pending");
        data.putObject("metadata").put("refund_id", refund.getId().toString());
        assertThat(
                        store.webhook(
                                id,
                                "stripe",
                                new com.example.omp.payment.provider.ProviderWebhookEvent(
                                        "evt-" + id, "refund.updated", data, "pi-" + id)))
                .isTrue();
        assertThat(store.read(id, refund.getId()).getAttentionReason()).isNull();
        assertThat(store.dueRecovery())
                .anyMatch(candidate -> candidate.refundId().equals(refund.getId()));
        var first = store.claimRecovery(id, refund.getId(), "stripe", false);
        assertThat(first.lookupReference()).isEqualTo("re_late");
        assertThat(store.claimRecovery(id, refund.getId(), "stripe", true)).isNull();
        jdbc.update(
                "UPDATE payments.refund SET attempt_until = now() - interval '1 second', next_attempt_at = now() WHERE id = ?",
                refund.getId());
        var second = store.claimRecovery(id, refund.getId(), "stripe", false);
        store.complete(
                first, com.example.omp.payment.provider.ProviderRefundResult.succeeded("re_late"));
        assertThat(store.read(id, refund.getId()).getStatus())
                .isEqualTo(com.example.omp.payment.domain.RefundStatus.PENDING);
        store.complete(
                second, com.example.omp.payment.provider.ProviderRefundResult.succeeded("re_late"));
        assertThat(store.read(id, refund.getId()).getStatus())
                .isEqualTo(com.example.omp.payment.domain.RefundStatus.COMPLETED);
    }

    @Test
    void operatorAuditCommitsBeforeVerifiedGetAndKeepsOriginalRefundIdentity() {
        UUID id = confirmedPayment();
        var state = context.getBean(RefundStore.class);
        var refund =
                state.prepare(id, 400L, null, "original", UUID.randomUUID().toString(), "stripe");
        jdbc.update(
                "UPDATE payments.refund SET created_at = now() - interval '21 hours', attention_reason = 'REPLAY_WINDOW_EXPIRED' WHERE id = ?",
                refund.getId());
        var provider = context.getBean(com.example.omp.payment.provider.PaymentProvider.class);
        org.mockito.Mockito.reset(provider);
        org.mockito.Mockito.when(provider.providerName()).thenReturn("stripe");
        org.mockito.Mockito.when(
                        provider.lookupRefund(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.eq("re_operator"),
                                org.mockito.ArgumentMatchers.eq(refund.getId())))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM payments.refund_reconciliation_audit WHERE refund_id = ? AND outcome = 'IN_PROGRESS' AND actor_subject = 'operator-it'",
                                                    Long.class,
                                                    refund.getId()))
                                    .isEqualTo(1);
                            return com.example.omp.payment.provider.ProviderRefundResult.pending(
                                    "re_operator");
                        })
                .thenReturn(
                        com.example.omp.payment.provider.ProviderRefundResult.succeeded(
                                "re_operator"));
        var jwt =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
                        .header("alg", "none")
                        .subject("operator-it")
                        .issuer("https://issuer.example")
                        .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.oauth2.server.resource.authentication
                                .JwtAuthenticationToken(
                                jwt,
                                List.of(
                                        new org.springframework.security.core.authority
                                                .SimpleGrantedAuthority("ROLE_admin"))));
        try {
            var service = context.getBean(RefundReconciliationService.class);
            var pending = service.reconcile(id, refund.getId(), "re_operator", "ledger review");
            assertThat(pending.audit().outcome()).isEqualTo("PENDING");
            assertThat(state.read(id, refund.getId()).getProviderReference())
                    .isEqualTo("re_operator");
            assertThat(state.read(id, refund.getId()).getAttentionReason()).isNull();
            var completed = service.reconcile(id, refund.getId(), "re_operator", "follow-up");
            assertThat(completed.audit().outcome()).isEqualTo("COMPLETED");
            assertThat(
                            service.reconcile(id, refund.getId(), "re_operator", "repeat")
                                    .audit()
                                    .outcome())
                    .isEqualTo("NO_CHANGE");
            assertThat(service.history(id, refund.getId())).hasSize(3);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT refunded_amount FROM payments.payment WHERE id = ?",
                                    java.math.BigDecimal.class,
                                    id))
                    .isEqualByComparingTo("4");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM payments.outbox_event WHERE aggregate_id = ?",
                                    Long.class,
                                    id.toString()))
                    .isEqualTo(2);
            assertThat(state.read(id, refund.getId()).getProviderKey())
                    .isEqualTo(refund.getProviderKey());
            org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(2))
                    .lookupRefund(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.eq("re_operator"),
                            org.mockito.ArgumentMatchers.eq(refund.getId()));
            org.mockito.Mockito.verify(provider, org.mockito.Mockito.never())
                    .refund(org.mockito.ArgumentMatchers.any());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void reconciliationAuditAndFinancialCompletionRollbackTogether() {
        UUID id = confirmedPayment();
        var state = context.getBean(RefundStore.class);
        var refund = state.prepare(id, 400L, null, null, UUID.randomUUID().toString(), "stripe");
        var store = context.getBean(RefundReconciliationStore.class);
        var claim =
                store.begin(
                        id, refund.getId(), "re_rollback", "stripe", "operator-it", null, "review");
        var result = com.example.omp.payment.provider.ProviderRefundResult.succeeded("re_rollback");
        var transactions =
                new org.springframework.transaction.support.TransactionTemplate(
                        context.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        status -> {
                                            store.complete(claim, result);
                                            throw new IllegalStateException("rollback");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM payments.refund_reconciliation_audit WHERE id = ?",
                                String.class,
                                claim.auditId()))
                .isEqualTo("IN_PROGRESS");
        assertThat(state.read(id, refund.getId()).getStatus())
                .isEqualTo(com.example.omp.payment.domain.RefundStatus.PENDING);
        assertThat(state.read(id, refund.getId()).getProviderReference()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments.outbox_event WHERE aggregate_id = ?",
                                Long.class,
                                id.toString()))
                .isEqualTo(1);
        store.complete(claim, result);
        store.complete(claim, result);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM payments.refund_reconciliation_audit WHERE id = ?",
                                String.class,
                                claim.auditId()))
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payments.outbox_event WHERE aggregate_id = ?",
                                Long.class,
                                id.toString()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT refunded_amount FROM payments.payment WHERE id = ?",
                                java.math.BigDecimal.class,
                                id))
                .isEqualByComparingTo("4");
    }

    @Test
    void providerReferenceCannotBeReusedByAnotherLocalRefund() {
        UUID id = confirmedPayment();
        var state = context.getBean(RefundStore.class);
        var first = state.prepare(id, 400L, null, null, UUID.randomUUID().toString(), "stripe");
        state.complete(
                state.claim(id, first.getId()),
                com.example.omp.payment.provider.ProviderRefundResult.succeeded("re_guard"));
        var second = state.prepare(id, 400L, null, null, UUID.randomUUID().toString(), "stripe");
        var store = context.getBean(RefundReconciliationStore.class);
        var blocked =
                store.begin(
                        id, second.getId(), "re_guard", "stripe", "operator-it", null, "review");
        assertThat(blocked.attempt()).isNull();
        assertThat(store.read(id, second.getId(), blocked.auditId()).audit().detailCode())
                .isEqualTo("REFERENCE_IN_USE");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE payments.refund SET provider_reference = 're_guard' WHERE id = ?",
                                        second.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT refunded_amount FROM payments.payment WHERE id = ?",
                                java.math.BigDecimal.class,
                                id))
                .isEqualByComparingTo("4");
        assertThat(state.read(id, second.getId()).getStatus())
                .isEqualTo(com.example.omp.payment.domain.RefundStatus.PENDING);
    }
}
