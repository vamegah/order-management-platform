package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.payment.domain.*;
import com.example.omp.payment.events.PaymentEventPublisher;
import com.example.omp.payment.grpc.PaymentServiceImpl;
import com.example.omp.payment.grpc.handler.*;
import com.example.omp.payment.provider.*;
import com.example.omp.payment.repository.*;
import com.example.omp.payment.service.*;
import com.example.omp.payment.web.RefundController;
import com.example.omp.proto.payment.*;
import com.example.omp.shared.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.*;
import io.grpc.inprocess.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real domain/store/executor and wire adapters; persistence and provider are controlled here. */
class RefundRetryTest {
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final PaymentProvider provider = mock(PaymentProvider.class);
    private final PaymentEventPublisher outbox = mock(PaymentEventPublisher.class);
    private final Map<UUID, com.example.omp.payment.domain.Refund> records = new HashMap<>();
    private final UUID id = UUID.randomUUID();
    private final com.example.omp.payment.domain.Payment payment =
            new com.example.omp.payment.domain.Payment(
                    UUID.randomUUID(), UUID.randomUUID(), Money.usd("10"), "charge");
    private final RefundStore store =
            new RefundStore(payments, refunds, outbox, mock(PaymentCreationLock.class));
    private final RefundApplicationService service = new RefundApplicationService(store, provider);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(payment, "id", id);
        ReflectionTestUtils.setField(payment, "createdAt", Instant.now());
        payment.setProvider("stripe");
        payment.confirm("pi-1");
        when(provider.providerName()).thenReturn("stripe");
        when(payments.lockForRefund(id)).thenReturn(Optional.of(payment));
        when(refunds.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(0))));
        when(refunds.findByIdempotencyKey(any()))
                .thenAnswer(
                        call ->
                                records.values().stream()
                                        .filter(
                                                r ->
                                                        r.getIdempotencyKey()
                                                                .equals(call.getArgument(0)))
                                        .findFirst());
        when(refunds.findByPaymentIdAndProviderReference(any(), any()))
                .thenAnswer(
                        call ->
                                records.values().stream()
                                        .filter(
                                                r ->
                                                        r.getPaymentId().equals(call.getArgument(0))
                                                                && Objects.equals(
                                                                        r.getProviderReference(),
                                                                        call.getArgument(1)))
                                        .findFirst());
        when(refunds.existsByPaymentIdAndStatus(any(), any()))
                .thenAnswer(
                        call ->
                                records.values().stream()
                                        .anyMatch(
                                                r ->
                                                        r.getPaymentId().equals(call.getArgument(0))
                                                                && r.getStatus()
                                                                        == call.getArgument(1)));
        when(refunds.saveAndFlush(any()))
                .thenAnswer(
                        call -> {
                            var refund =
                                    (com.example.omp.payment.domain.Refund) call.getArgument(0);
                            ReflectionTestUtils.setField(refund, "id", UUID.randomUUID());
                            ReflectionTestUtils.setField(refund, "createdAt", Instant.now());
                            records.put(refund.getId(), refund);
                            return refund;
                        });
    }

    @Test
    void retryAfterLostProviderReplyUsesOneIntentAndStableProviderKey() {
        when(provider.refund(any()))
                .thenThrow(new IllegalStateException("sensitive provider text"))
                .thenReturn(ProviderRefundResult.succeeded("re-1"));
        var pending = service.refund(id, 400, "requested", "refund-key");
        assertThat(pending.getStatus())
                .isEqualTo(com.example.omp.payment.domain.RefundStatus.PENDING);
        verifyNoInteractions(outbox);
        assertThat(service.refund(id, 400, "requested", "refund-key")).isSameAs(pending);
        assertThat(pending.getStatus())
                .isEqualTo(com.example.omp.payment.domain.RefundStatus.COMPLETED);
        assertThat(payment.getRemainingRefundable().getAmount()).isEqualByComparingTo("6");
        service.refund(id, 400, "requested", "refund-key");
        var requests = org.mockito.ArgumentCaptor.forClass(RefundRequest.class);
        verify(provider, times(2)).refund(requests.capture());
        assertThat(requests.getAllValues())
                .allSatisfy(
                        r ->
                                assertThat(r.idempotencyKey())
                                        .isEqualTo("omp-refund-" + pending.getId()));
        verify(outbox, times(1)).publishRefund(eq(payment), any());
        assertThat(records).hasSize(1);
    }

    @Test
    void pendingProviderResponseReservesBalanceUntilWebhookAndBindsDefaultAmountOnce()
            throws Exception {
        when(provider.refund(any())).thenReturn(ProviderRefundResult.pending("re-1"));
        var pending = service.refund(id, (Money) null, "requested", "all");
        assertThat(pending.getProviderReference()).isEqualTo("re-1");
        assertThatThrownBy(() -> service.refund(id, 100, null, "other"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.webhook(id, "stripe", webhook(pending, "succeeded", 1000, "usd", "re-1")))
                .isTrue();
        assertThat(payment.getStatus())
                .isEqualTo(com.example.omp.payment.domain.PaymentStatus.REFUNDED);
        assertThat(service.refund(id, (Money) null, "requested", "all")).isSameAs(pending);
        assertThat(store.webhook(id, "stripe", webhook(pending, "succeeded", 1000, "usd", "re-1")))
                .isFalse();
        verify(provider).refund(any());
        verify(outbox).publishRefund(eq(payment), any());
    }

    @Test
    void partialThenRemainingRefundsUseSeparateIntentsAndNeverOverRefund() {
        when(provider.refund(any()))
                .thenReturn(ProviderRefundResult.succeeded("re-1"))
                .thenReturn(ProviderRefundResult.succeeded("re-2"));
        service.refund(id, 400, null, "part");
        var remaining = service.refund(id, (Money) null, null, "rest");
        assertThat(remaining.getAmountMinor()).isEqualTo(600);
        assertThat(payment.getRemainingRefundable().getAmount()).isZero();
        assertThat(service.refund(id, (Money) null, null, "rest")).isSameAs(remaining);
        assertThatThrownBy(() -> service.refund(id, 1, null, "excess"))
                .isInstanceOf(IllegalStateException.class);
        verify(provider, times(2)).refund(any());
    }

    @Test
    void webhookBeforeProviderReplyCompletesOnceAndFencesLateReply() throws Exception {
        var refund = store.prepare(id, 400L, null, null, "key", "stripe");
        var attempt = store.claim(id, refund.getId());
        assertThat(store.webhook(id, "stripe", webhook(refund, "succeeded", 400, "usd", "re-1")))
                .isTrue();
        store.complete(attempt, ProviderRefundResult.succeeded("re-1"));
        verify(outbox).publishRefund(eq(payment), any());
        assertThat(payment.getRemainingRefundable().getAmount()).isEqualByComparingTo("6");
    }

    @Test
    void expiredLeaseFencesStaleAcknowledgementAndPreservesProviderKey() {
        var refund = store.prepare(id, 400L, null, null, "key", "stripe");
        var first = store.claim(id, refund.getId());
        assertThat(store.claim(id, refund.getId())).isNull();
        ReflectionTestUtils.setField(refund, "attemptUntil", Instant.EPOCH);
        var second = store.claim(id, refund.getId());
        assertThat(first.request()).isEqualTo(second.request());
        store.complete(first, ProviderRefundResult.succeeded("re-stale"));
        verifyNoInteractions(outbox);
        store.complete(second, ProviderRefundResult.succeeded("re-1"));
        verify(outbox).publishRefund(eq(payment), any());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "amount",
                "currency",
                "payment",
                "provider",
                "reference",
                "metadata",
                "fraction"
            })
    void mismatchedWebhookCannotMutateRefund(String changed) throws Exception {
        var refund = store.prepare(id, 400L, null, null, "key", "stripe");
        var attempt = store.claim(id, refund.getId());
        store.complete(attempt, ProviderRefundResult.pending("re-1"));
        var event =
                webhook(
                        refund,
                        "succeeded",
                        changed.equals("amount") ? 500 : 400,
                        changed.equals("currency") ? "eur" : "usd",
                        changed.equals("reference") ? "re-other" : "re-1");
        var data = (com.fasterxml.jackson.databind.node.ObjectNode) event.data();
        if (changed.equals("metadata"))
            ((com.fasterxml.jackson.databind.node.ObjectNode) data.get("metadata"))
                    .put("refund_id", UUID.randomUUID().toString());
        if (changed.equals("fraction")) data.put("amount", 400.5);
        if (changed.equals("payment"))
            event = new ProviderWebhookEvent(event.eventId(), event.eventType(), data, "pi-other");
        if (changed.equals("reference")) {
            assertThatThrownBy(() -> store.webhook(id, "stripe", eventCopy(data)))
                    .isInstanceOf(IllegalArgumentException.class);
        } else
            assertThat(store.webhook(id, changed.equals("provider") ? "noop" : "stripe", event))
                    .isFalse();
        assertThat(refund.getStatus())
                .isEqualTo(com.example.omp.payment.domain.RefundStatus.PENDING);
        verifyNoInteractions(outbox);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "currency", "reason", "payment"})
    void changedReplayIsRejectedEvenAfterCompletion(String changed) {
        when(provider.refund(any())).thenReturn(ProviderRefundResult.succeeded("re-1"));
        service.refund(id, 400, "original", "key");
        UUID requestId = id;
        if (changed.equals("payment")) {
            requestId = UUID.randomUUID();
            when(payments.lockForRefund(requestId)).thenReturn(Optional.of(payment));
        }
        UUID finalId = requestId;
        assertThatThrownBy(
                        () ->
                                service.refund(
                                        finalId,
                                        changed.equals("currency")
                                                ? Money.of(
                                                        new java.math.BigDecimal("4"),
                                                        java.util.Currency.getInstance("EUR"))
                                                : Money.usd(changed.equals("amount") ? "5" : "4"),
                                        changed.equals("reason") ? "other" : "original",
                                        "key"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(provider).refund(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "line\nbreak", "oversized"})
    void invalidKeysAreRejectedBeforePersistence(String key) {
        String invalid = key.equals("oversized") ? "x".repeat(256) : key;
        assertThatThrownBy(() -> service.refund(id, 400, null, invalid))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(payments, refunds, outbox);
        verify(provider, never()).refund(any());
    }

    @Test
    void failedOutcomePreservesRequestBindingAndDoesNotChangePayment() throws Exception {
        when(provider.refund(any())).thenReturn(ProviderRefundResult.pending("re-1"));
        var refund = service.refund(id, 400, "original", "key");
        assertThat(store.webhook(id, "stripe", webhook(refund, "failed", 400, "usd", "re-1")))
                .isTrue();
        assertThat(refund.getStatus())
                .isEqualTo(com.example.omp.payment.domain.RefundStatus.FAILED);
        assertThat(service.refund(id, 400, "original", "key")).isSameAs(refund);
        assertThat(payment.getRemainingRefundable().getAmount()).isEqualByComparingTo("10");
        verifyNoInteractions(outbox);
    }

    @Test
    void providerMismatchAndAmbientTransactionsRejectExternalMutation() {
        payment.setProvider("noop");
        assertThatThrownBy(() -> service.refund(id, 400, null, "key"))
                .isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.refund(id, 400, null, "key"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.clear();
        }
        verify(provider, never()).refund(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void restAndRealGrpcSharePendingAndCompletedOutcomes() throws Exception {
        when(provider.refund(any())).thenReturn(ProviderRefundResult.pending("re-1"));
        var controller = new RefundController(service);
        var rest =
                controller.refund(
                        id, new RefundController.RefundHttpRequest(400, "requested", "key"));
        assertThat(rest.getStatusCode().value()).isEqualTo(202);
        String name = InProcessServerBuilder.generateName();
        var server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                new PaymentServiceImpl(
                                        mock(PaymentIntentHandler.class),
                                        mock(PaymentQueryHandler.class),
                                        new RefundHandler(service, new MoneyConverter()),
                                        mock(PaymentEventStreamHandler.class)))
                        .build()
                        .start();
        var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            var client = PaymentServiceGrpc.newBlockingStub(channel);
            var response =
                    client.refundPayment(
                            RefundPaymentRequest.newBuilder()
                                    .setPaymentId(id.toString())
                                    .setReason("requested")
                                    .setIdempotencyKey("key")
                                    .build());
            assertThat(response.getId()).isEqualTo(rest.getBody().id().toString());
            assertThat(response.getStatus())
                    .isEqualTo(com.example.omp.proto.payment.RefundStatus.REFUND_STATUS_PENDING);
            var pending = records.get(UUID.fromString(response.getId()));
            store.webhook(id, "stripe", webhook(pending, "succeeded", 400, "usd", "re-1"));
            assertThat(
                            client.refundPayment(
                                            RefundPaymentRequest.newBuilder()
                                                    .setPaymentId(id.toString())
                                                    .setReason("requested")
                                                    .setIdempotencyKey("key")
                                                    .build())
                                    .getStatus())
                    .isEqualTo(com.example.omp.proto.payment.RefundStatus.REFUND_STATUS_COMPLETED);
            assertThatThrownBy(
                            () ->
                                    client.refundPayment(
                                            RefundPaymentRequest.newBuilder()
                                                    .setPaymentId(id.toString())
                                                    .setIdempotencyKey("key")
                                                    .setReason("different")
                                                    .build()))
                    .isInstanceOfSatisfying(
                            StatusRuntimeException.class,
                            e ->
                                    assertThat(e.getStatus().getCode())
                                            .isEqualTo(Status.Code.INVALID_ARGUMENT));
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
        assertThat(
                        controller
                                .refund(
                                        id,
                                        new RefundController.RefundHttpRequest(
                                                400, "requested", "key"))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        verify(outbox).publishRefund(eq(payment), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "expired"})
    void unboundLegacyOrExpiredIntentsCannotReplayProviderPost(String state) {
        var refund = store.prepare(id, 400L, null, null, "key", "stripe");
        if (state.equals("legacy")) ReflectionTestUtils.setField(refund, "providerKey", null);
        else
            ReflectionTestUtils.setField(
                    refund, "createdAt", Instant.now().minusSeconds(21 * 3600));
        assertThatThrownBy(() -> service.refund(id, 400, null, "key"))
                .isInstanceOf(IllegalStateException.class);
        verify(provider, never()).refund(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void webhookReceiptDeduplicationAndAggregateEchoDoNotDoubleApplyMoney() {
        var refund = store.prepare(id, 400L, null, null, "key", "stripe");
        var event = webhook(refund, "succeeded", 400, "usd", "re-1");
        var receipts = mock(PaymentProviderEventRepository.class);
        var receiptKeys = new HashSet<String>();
        when(receipts.insertIfAbsent(any(), any(), any(), any(), any()))
                .thenAnswer(call -> receiptKeys.add(call.getArgument(2)) ? 1 : 0);
        when(receipts.findById(any())).thenReturn(Optional.of(new PaymentProviderEvent()));
        when(payments.lockByProviderReference("stripe", "pi-1")).thenReturn(Optional.of(payment));
        var processor =
                new PaymentProviderEventService(
                        receipts,
                        payments,
                        mock(com.example.omp.payment.outbox.OutboxWriter.class),
                        store);
        assertThat(processor.process("stripe", event))
                .isEqualTo(PaymentProviderEventService.ProcessResult.ACCEPTED);
        assertThat(processor.process("stripe", event))
                .isEqualTo(PaymentProviderEventService.ProcessResult.DUPLICATE);
        var echo =
                new ProviderWebhookEvent(
                        UUID.randomUUID().toString(), "charge.refunded", event.data(), "pi-1");
        assertThat(processor.process("stripe", echo))
                .isEqualTo(PaymentProviderEventService.ProcessResult.IGNORED);
        assertThat(payment.getRemainingRefundable().getAmount()).isEqualByComparingTo("6");
        verify(outbox).publishRefund(eq(payment), any());
    }

    private ProviderWebhookEvent webhook(
            com.example.omp.payment.domain.Refund refund,
            String status,
            long amount,
            String currency,
            String reference) {
        var data = new ObjectMapper().createObjectNode();
        data.put("id", reference)
                .put("status", status)
                .put("amount", amount)
                .put("currency", currency);
        data.putObject("metadata").put("refund_id", refund.getId().toString());
        return eventCopy(data);
    }

    private ProviderWebhookEvent eventCopy(com.fasterxml.jackson.databind.JsonNode data) {
        return new ProviderWebhookEvent(
                UUID.randomUUID().toString(), "refund.updated", data, "pi-1");
    }
}
