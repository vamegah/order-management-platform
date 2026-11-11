package com.example.omp.payment.service;

import com.example.omp.payment.domain.MinorUnits;
import com.example.omp.payment.domain.Payment;
import com.example.omp.payment.domain.Refund;
import com.example.omp.payment.domain.RefundStatus;
import com.example.omp.payment.error.PaymentNotFoundException;
import com.example.omp.payment.events.PaymentEventPublisher;
import com.example.omp.payment.provider.ProviderRefundResult;
import com.example.omp.payment.provider.ProviderWebhookEvent;
import com.example.omp.payment.provider.RefundRequest;
import com.example.omp.payment.repository.PaymentRepository;
import com.example.omp.payment.repository.RefundRepository;
import com.example.omp.shared.domain.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short local transactions for provider-backed refunds. Provider calls belong to the executor. */
@Service
public class RefundStore {
    public record Attempt(
            UUID paymentId,
            UUID refundId,
            UUID lease,
            RefundRequest request,
            String lookupReference) {}

    public record Candidate(UUID paymentId, UUID refundId) {}

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentEventPublisher events;
    private final PaymentCreationLock keys;

    public RefundStore(
            PaymentRepository payments,
            RefundRepository refunds,
            PaymentEventPublisher events,
            PaymentCreationLock keys) {
        this.payments = payments;
        this.refunds = refunds;
        this.events = events;
        this.keys = keys;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Refund prepare(
            UUID paymentId, Long minor, Money amount, String reason, String key, String provider) {
        if (key == null || !key.matches("[!-~]{1,255}"))
            throw new IllegalArgumentException("A bounded printable idempotency key is required");
        if (reason != null && reason.length() > 500)
            throw new IllegalArgumentException("Refund reason is too long");
        if (reason != null && reason.isEmpty()) reason = null;
        keys.acquire(key);
        Payment payment = lock(paymentId);
        Refund existing = refunds.findByIdempotencyKey(key).orElse(null);
        Money requested = minor == null ? amount : MinorUnits.toMoney(minor, payment.getCurrency());
        if (existing != null) {
            if (!existing.getPaymentId().equals(paymentId)
                    || (requested != null && !sameAmount(existing.getAmount(), requested))
                    || !Objects.equals(existing.getReason(), reason))
                throw new IllegalArgumentException(
                        "Idempotency key reused with different refund details");
            if (existing.getStatus() == RefundStatus.PENDING) {
                requireProvider(payment, provider);
                if (existing.getProviderKey() == null)
                    throw new IllegalStateException(
                            "Legacy pending refund requires reconciliation");
            }
            return existing;
        }
        payment.requireRefundAllowed();
        requireProvider(payment, provider);
        if (!payment.getStatus().isRefundable())
            throw new IllegalStateException("Payment is not refundable");
        // An uncertain provider outcome reserves the balance until reconciled. In particular,
        // another key cannot spend the same balance after a timeout or a lost response.
        if (refunds.existsByPaymentIdAndStatus(paymentId, RefundStatus.PENDING))
            throw new IllegalStateException("A refund is already pending for this payment");
        if (requested == null) requested = payment.getRemainingRefundable();
        if (!payment.getCurrency().equals(requested.getCurrencyCode())
                || MinorUnits.fromMoney(requested) <= 0
                || requested.getAmount().compareTo(payment.getRemainingRefundable().getAmount())
                        > 0) throw new IllegalArgumentException("Invalid refund amount");
        if (payment.getProviderReference() == null || payment.getProviderReference().isBlank())
            throw new IllegalStateException("Payment has no provider reference");
        Refund refund = refunds.saveAndFlush(new Refund(payment, requested, reason, key));
        refund.initializeProviderKey();
        return refund;
    }

    @Transactional
    public Attempt claim(UUID paymentId, UUID refundId) {
        Payment payment = lock(paymentId);
        Refund refund = find(paymentId, refundId);
        if (refund.getStatus() != RefundStatus.PENDING || refund.getProviderReference() != null)
            return null;
        if (refund.getProviderKey() == null)
            throw new IllegalStateException("Legacy pending refund requires reconciliation");
        // Stripe v1 can prune idempotency keys after 24 hours. Stop POST retries well
        // before that boundary; continue accepting correlated provider webhooks.
        if ("stripe".equals(payment.getProvider())
                && refund.getCreatedAt().plusSeconds(20 * 60 * 60).isBefore(Instant.now()))
            throw new IllegalStateException(
                    "Refund replay window expired; provider reconciliation required");
        UUID lease = refund.claimAttempt(Instant.now());
        if (lease == null) return null;
        return new Attempt(
                paymentId,
                refundId,
                lease,
                new RefundRequest(
                        paymentId,
                        payment.getProviderReference(),
                        refund.getAmountMinor(),
                        refund.getCurrency(),
                        refund.getProviderKey(),
                        refund.getReason()),
                null);
    }

    @Transactional(readOnly = true)
    public java.util.List<Candidate> dueRecovery() {
        return refunds.findDueRecovery().stream()
                .map(candidate -> new Candidate(candidate.getPaymentId(), candidate.getId()))
                .toList();
    }

    /** Payment-first locking matches webhook/finalization ordering. A busy row is skipped. */
    @Transactional
    public Attempt claimRecovery(UUID paymentId, UUID refundId, String provider, boolean force) {
        Payment payment = payments.tryLockForRefund(paymentId).orElse(null);
        if (payment == null) return null;
        Refund refund = find(paymentId, refundId);
        Instant now = Instant.now();
        if (refund.getStatus() != RefundStatus.PENDING || (!force && !refund.isDue(now)))
            return null;
        if (!provider.equals(payment.getProvider())) {
            refund.requireAttention(Refund.RecoveryBlock.PROVIDER_MISMATCH);
            return null;
        }
        if (refund.getProviderKey() == null) {
            refund.requireAttention(Refund.RecoveryBlock.LEGACY_INTENT);
            return null;
        }
        if (!payment.getStatus().isRefundable()
                || payment.getProviderReference() == null
                || payment.getProviderReference().isBlank()
                || refund.getAmount()
                                .getAmount()
                                .compareTo(payment.getRemainingRefundable().getAmount())
                        > 0) {
            refund.requireAttention(Refund.RecoveryBlock.PAYMENT_STATE_MISMATCH);
            return null;
        }
        if ("stripe".equals(provider)
                && refund.getProviderReference() == null
                && refund.getCreatedAt().plusSeconds(20 * 3600).isBefore(now)) {
            refund.requireAttention(Refund.RecoveryBlock.REPLAY_WINDOW_EXPIRED);
            return null;
        }
        UUID lease = refund.claimAttempt(now);
        if (lease == null) return null;
        refund.clearAttention();
        return new Attempt(
                paymentId,
                refundId,
                lease,
                new RefundRequest(
                        paymentId,
                        payment.getProviderReference(),
                        refund.getAmountMinor(),
                        refund.getCurrency(),
                        refund.getProviderKey(),
                        refund.getReason()),
                refund.getProviderReference());
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return refunds.countByStatus(RefundStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public long attentionCount() {
        return refunds.countByStatusAndAttentionReasonIsNotNull(RefundStatus.PENDING);
    }

    @Transactional
    public Refund complete(Attempt attempt, ProviderRefundResult result) {
        Payment payment = lock(attempt.paymentId());
        Refund refund = find(attempt.paymentId(), attempt.refundId());
        if (!refund.ownsAttempt(attempt.lease())) return refund;
        if (attempt.lookupReference() != null
                && result.status() != ProviderRefundResult.Status.ERROR
                && !attempt.lookupReference().equals(result.providerReference()))
            throw new IllegalArgumentException("Provider refund identity mismatch");
        apply(payment, refund, result);
        refund.recordOutcome(result.status() == ProviderRefundResult.Status.ERROR, Instant.now());
        refund.releaseAttempt();
        return refund;
    }

    /** Joins the webhook receipt transaction. A webhook may arrive before the HTTP reply. */
    @Transactional
    public boolean webhook(UUID paymentId, String provider, ProviderWebhookEvent event) {
        Payment payment = lock(paymentId);
        if (!provider.equals(payment.getProvider())
                || !Objects.equals(event.paymentRef(), payment.getProviderReference()))
            return false;
        var data = event.data();
        String reference = data.path("id").asText("");
        if (reference.isBlank()) return false;
        Refund refund;
        String localId = data.path("metadata").path("refund_id").asText("");
        if (!localId.isEmpty()) {
            try {
                refund = refunds.findById(UUID.fromString(localId)).orElse(null);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        } else
            refund = refunds.findByPaymentIdAndProviderReference(paymentId, reference).orElse(null);
        if (refund == null
                || !paymentId.equals(refund.getPaymentId())
                || refund.getStatus() != RefundStatus.PENDING) return false;
        if (!data.path("amount").isIntegralNumber()
                || !data.path("amount").canConvertToLong()
                || data.path("amount").longValue() != refund.getAmountMinor()
                || !refund.getCurrency().equalsIgnoreCase(data.path("currency").asText("")))
            return false;
        String status = data.path("status").asText("");
        if (!java.util.Set.of("succeeded", "failed", "canceled", "pending", "requires_action")
                .contains(status)) return false;
        refund.correlate(reference);
        if (status.equals("pending") || status.equals("requires_action")) {
            if (refund.getProviderKey() != null) refund.clearAttention();
            return true;
        }
        apply(
                payment,
                refund,
                status.equals("succeeded")
                        ? ProviderRefundResult.succeeded(reference)
                        : ProviderRefundResult.failed("Provider rejected refund"));
        refund.recordOutcome(false, Instant.now());
        refund.releaseAttempt();
        return true;
    }

    private void apply(Payment payment, Refund refund, ProviderRefundResult result) {
        switch (result.status()) {
            case SUCCEEDED -> {
                refund.correlate(result.providerReference());
                payment.applyRefund(refund.getAmount());
                refund.markSucceeded(result.providerReference());
                events.publishRefund(payment, refund);
            }
            case PENDING -> refund.correlate(result.providerReference());
            // Keep the original reason as part of the idempotent request binding.
            case FAILED -> refund.markFailed(null);
            case ERROR -> {}
        }
    }

    @Transactional(readOnly = true)
    public Refund read(UUID paymentId, UUID refundId) {
        return find(paymentId, refundId);
    }

    private Payment lock(UUID id) {
        return payments.lockForRefund(id)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found"));
    }

    private Refund find(UUID paymentId, UUID refundId) {
        Refund refund =
                refunds.findById(refundId)
                        .orElseThrow(() -> new PaymentNotFoundException("Refund not found"));
        if (!refund.getPaymentId().equals(paymentId))
            throw new PaymentNotFoundException("Refund not found");
        return refund;
    }

    private static boolean sameAmount(Money a, Money b) {
        return a.getCurrencyCode().equals(b.getCurrencyCode())
                && a.getAmount().compareTo(b.getAmount()) == 0;
    }

    private static void requireProvider(Payment payment, String provider) {
        if (provider == null || !provider.equals(payment.getProvider()))
            throw new IllegalStateException("Payment provider requires reconciliation");
    }
}
