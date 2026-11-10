package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.payment.grpc.handler.PaymentIntentHandler;
import com.example.omp.payment.service.RefundStore;
import com.example.omp.proto.payment.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** The actual payment row lock serializes refund admission and shipment decisions. */
@Testcontainers
class PaymentFulfillmentFenceIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = PaymentCreationConcurrencyIT.POSTGRES;

    @Configuration
    @EnableMethodSecurity
    @Import(PaymentCreationConcurrencyIT.Beans.class)
    static class Beans {}

    private static AnnotationConfigApplicationContext context;
    private static PaymentIntentHandler handler;
    private static RefundStore refunds;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        open();
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    @BeforeEach
    void authenticate() {
        reconciler();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void open() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource("fence-test", Map.of("payment.provider", "stripe")));
        context.register(Beans.class);
        context.refresh();
        handler = context.getBean(PaymentIntentHandler.class);
        refunds = context.getBean(RefundStore.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    private static void reconciler() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "test-reconciler",
                                "unused",
                                List.of(new SimpleGrantedAuthority("SCOPE_order.reconcile"))));
    }

    private Payment confirmed() {
        var payment =
                handler.create(
                        CreatePaymentIntentRequest.newBuilder()
                                .setOrderId(UUID.randomUUID().toString())
                                .setCustomerId(UUID.randomUUID().toString())
                                .setIdempotencyKey(UUID.randomUUID().toString())
                                .setAmount(
                                        com.example.omp.proto.common.Money.newBuilder()
                                                .setAmount("10")
                                                .setCurrency("USD"))
                                .build());
        return handler.confirm(
                ConfirmPaymentRequest.newBuilder()
                        .setPaymentId(payment.getId())
                        .setProviderReference("pi-" + UUID.randomUUID())
                        .build());
    }

    private FulfillmentDecision decide(Payment payment, FulfillmentAction action) {
        return handler.decideFulfillment(
                FulfillmentDecisionRequest.newBuilder()
                        .setPaymentId(payment.getId())
                        .setOrderId(payment.getOrderId())
                        .setAction(action)
                        .build());
    }

    private void prepareRefund(Payment payment) {
        refunds.prepare(
                UUID.fromString(payment.getId()),
                null,
                null,
                "Race regression",
                UUID.randomUUID().toString(),
                "stripe");
    }

    @RepeatedTest(5)
    void concurrentRefundAndShipmentAdmissionCannotBothWin() throws Exception {
        var payment = confirmed();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var shipping =
                    executor.submit(
                            () -> {
                                start.await();
                                reconciler();
                                try {
                                    decide(payment, FulfillmentAction.FULFILLMENT_ACTION_BEGIN);
                                    return true;
                                } catch (IllegalStateException refundWon) {
                                    return false;
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            var refund =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    prepareRefund(payment);
                                    return true;
                                } catch (IllegalStateException shipmentWon) {
                                    return false;
                                }
                            });
            start.countDown();
            assertThat(shipping.get(15, TimeUnit.SECONDS))
                    .isNotEqualTo(refund.get(15, TimeUnit.SECONDS));
        }
        String state =
                jdbc.queryForObject(
                        "SELECT fulfillment_state FROM payments.payment WHERE id = ?",
                        String.class,
                        UUID.fromString(payment.getId()));
        long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM payments.refund WHERE payment_id = ?",
                        Long.class,
                        UUID.fromString(payment.getId()));
        assertThat(state.equals("FULFILLING") ? count == 0 : state.equals("OPEN") && count == 1)
                .isTrue();
    }

    @RepeatedTest(5)
    void cancellationAndShipmentCompletionProduceOneDurableDecision() throws Exception {
        var payment = confirmed();
        decide(payment, FulfillmentAction.FULFILLMENT_ACTION_BEGIN);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ship =
                    executor.submit(
                            () -> {
                                start.await();
                                reconciler();
                                try {
                                    return decide(
                                                    payment,
                                                    FulfillmentAction.FULFILLMENT_ACTION_SHIP)
                                            .getState();
                                } catch (IllegalStateException cancellationWon) {
                                    return "CANCELLING";
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            var cancel =
                    executor.submit(
                            () -> {
                                start.await();
                                reconciler();
                                try {
                                    return decide(
                                                    payment,
                                                    FulfillmentAction.FULFILLMENT_ACTION_CANCEL)
                                            .getState();
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            start.countDown();
            assertThat(ship.get(15, TimeUnit.SECONDS)).isEqualTo(cancel.get(15, TimeUnit.SECONDS));
        }
        var decision = decide(payment, FulfillmentAction.FULFILLMENT_ACTION_CANCEL);
        if (decision.getState().equals("SHIPPED")) {
            assertThatThrownBy(() -> prepareRefund(payment))
                    .isInstanceOf(IllegalStateException.class);
        } else {
            assertThat(decision.getState()).isEqualTo("CANCELLING");
            assertThatThrownBy(() -> decide(payment, FulfillmentAction.FULFILLMENT_ACTION_SHIP))
                    .isInstanceOf(IllegalStateException.class);
            decide(payment, FulfillmentAction.FULFILLMENT_ACTION_ALLOW_REFUND);
            prepareRefund(payment);
        }
    }

    @Test
    void anExistingUncertainRefundPreventsAnyInventoryCommitAuthorization() {
        var payment = confirmed();
        prepareRefund(payment);
        assertThatThrownBy(() -> decide(payment, FulfillmentAction.FULFILLMENT_ACTION_BEGIN))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT fulfillment_state FROM payments.payment WHERE id = ?",
                                String.class,
                                UUID.fromString(payment.getId())))
                .isEqualTo("OPEN");
    }

    @Test
    void restartingTheApplicationDoesNotExpireAFulfillmentFence() {
        var payment = confirmed();
        decide(payment, FulfillmentAction.FULFILLMENT_ACTION_BEGIN);
        context.close();
        open();
        assertThatThrownBy(() -> prepareRefund(payment)).isInstanceOf(IllegalStateException.class);
        var shipped = decide(payment, FulfillmentAction.FULFILLMENT_ACTION_SHIP);
        assertThat(shipped.getState()).isEqualTo("SHIPPED");
        assertThat(shipped.getPayment().getProviderReference())
                .isEqualTo(payment.getProviderReference());
        assertThat(decide(payment, FulfillmentAction.FULFILLMENT_ACTION_CANCEL).getState())
                .isEqualTo("SHIPPED");
    }

    @Test
    void foreignOrderAndUnprivilegedCallersCannotChangeTheDecision() {
        var payment = confirmed();
        assertThatThrownBy(
                        () ->
                                handler.decideFulfillment(
                                        FulfillmentDecisionRequest.newBuilder()
                                                .setPaymentId(payment.getId())
                                                .setOrderId(UUID.randomUUID().toString())
                                                .setAction(
                                                        FulfillmentAction.FULFILLMENT_ACTION_CANCEL)
                                                .build()))
                .isInstanceOf(IllegalArgumentException.class);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "ordinary-owner",
                                "unused",
                                List.of(new SimpleGrantedAuthority("ROLE_user"))));
        assertThatThrownBy(() -> decide(payment, FulfillmentAction.FULFILLMENT_ACTION_CANCEL))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT fulfillment_state FROM payments.payment WHERE id = ?",
                                String.class,
                                UUID.fromString(payment.getId())))
                .isEqualTo("OPEN");
    }
}
