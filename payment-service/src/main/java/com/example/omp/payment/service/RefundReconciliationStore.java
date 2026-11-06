package com.example.omp.payment.service;

import com.example.omp.payment.domain.*;
import com.example.omp.payment.domain.RefundReconciliationAudit.Outcome;
import com.example.omp.payment.provider.*;
import com.example.omp.payment.repository.*;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operator intent and result transactions; all provider reads are performed by the service. */
@Service
public class RefundReconciliationStore {
    public record Claim(UUID auditId, RefundStore.Attempt attempt) {}

    public record AuditView(
            UUID id,
            UUID refundId,
            String candidateReference,
            String actorSubject,
            String actorIssuer,
            String reason,
            String outcome,
            String detailCode,
            String createdAt,
            String completedAt) {
        static AuditView from(RefundReconciliationAudit audit) {
            return new AuditView(
                    audit.getId(),
                    audit.getRefundId(),
                    audit.getCandidateReference(),
                    audit.getActorSubject(),
                    audit.getActorIssuer(),
                    audit.getReason(),
                    audit.getOutcome().name(),
                    audit.getDetailCode(),
                    audit.getCreatedAt().toString(),
                    audit.getCompletedAt() == null ? null : audit.getCompletedAt().toString());
        }
    }

    public record Result(Refund refund, AuditView audit) {}

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final RefundReconciliationAuditRepository audits;
    private final RefundStore state;

    public RefundReconciliationStore(
            PaymentRepository payments,
            RefundRepository refunds,
            RefundReconciliationAuditRepository audits,
            RefundStore state) {
        this.payments = payments;
        this.refunds = refunds;
        this.audits = audits;
        this.state = state;
    }

    @Transactional
    public Claim begin(
            UUID paymentId,
            UUID refundId,
            String reference,
            String provider,
            String subject,
            String issuer,
            String reason) {
        Payment payment = lock(paymentId);
        Refund refund = state.read(paymentId, refundId);
        var audit =
                audits.saveAndFlush(
                        new RefundReconciliationAudit(refund, reference, subject, issuer, reason));
        if (refund.getProviderReference() != null
                && !reference.equals(refund.getProviderReference())) {
            audit.finish(Outcome.BLOCKED, "REFERENCE_CONFLICT");
        } else if (refund.getStatus() != RefundStatus.PENDING) {
            audit.finish(Outcome.NO_CHANGE, null);
        } else if (!provider.equals(payment.getProvider())) {
            refund.requireAttention(Refund.RecoveryBlock.PROVIDER_MISMATCH);
            audit.finish(Outcome.BLOCKED, "PROVIDER_MISMATCH");
        } else if (!("omp-refund-" + refundId).equals(refund.getProviderKey())) {
            refund.requireAttention(Refund.RecoveryBlock.LEGACY_INTENT);
            audit.finish(Outcome.BLOCKED, "LEGACY_INTENT");
        } else if (!canApply(payment, refund)) {
            refund.requireAttention(Refund.RecoveryBlock.PAYMENT_STATE_MISMATCH);
            audit.finish(Outcome.BLOCKED, "PAYMENT_STATE_MISMATCH");
        } else if (referenceInUse(paymentId, refundId, reference)) {
            audit.finish(Outcome.BLOCKED, "REFERENCE_IN_USE");
        } else {
            UUID lease = refund.claimAttempt(Instant.now());
            if (lease == null) audit.finish(Outcome.BUSY, null);
            else {
                audit.bindLease(lease);
                var request =
                        new RefundRequest(
                                paymentId,
                                payment.getProviderReference(),
                                refund.getAmountMinor(),
                                refund.getCurrency(),
                                refund.getProviderKey(),
                                refund.getReason());
                return new Claim(
                        audit.getId(),
                        new RefundStore.Attempt(paymentId, refundId, lease, request, reference));
            }
        }
        return new Claim(audit.getId(), null);
    }

    @Transactional
    public Result complete(Claim claim, ProviderRefundResult result) {
        var attempt = Objects.requireNonNull(claim.attempt());
        Payment payment = lock(attempt.paymentId());
        Refund refund = state.read(attempt.paymentId(), attempt.refundId());
        var audit = audits.findById(claim.auditId()).orElseThrow();
        if (!audit.getRefundId().equals(attempt.refundId())
                || !Objects.equals(audit.getLeaseId(), attempt.lease())
                || !audit.getCandidateReference().equals(attempt.lookupReference()))
            throw new IllegalArgumentException("Reconciliation claim mismatch");
        if (audit.getOutcome() != Outcome.IN_PROGRESS) return result(refund, audit);
        if (!refund.ownsAttempt(attempt.lease())) {
            audit.finish(Outcome.SUPERSEDED, null);
            return result(refund, audit);
        }
        if (result.status() != ProviderRefundResult.Status.ERROR
                && !attempt.lookupReference().equals(result.providerReference()))
            result = ProviderRefundResult.error("Provider refund identity unresolved");
        if (result.status() != ProviderRefundResult.Status.ERROR && !canApply(payment, refund)) {
            state.complete(attempt, ProviderRefundResult.error("Payment changed"));
            refund.requireAttention(Refund.RecoveryBlock.PAYMENT_STATE_MISMATCH);
            audit.finish(Outcome.BLOCKED, "PAYMENT_STATE_MISMATCH");
        } else if (result.status() != ProviderRefundResult.Status.ERROR
                && referenceInUse(
                        attempt.paymentId(), attempt.refundId(), attempt.lookupReference())) {
            state.complete(attempt, ProviderRefundResult.error("Provider reference already bound"));
            audit.finish(Outcome.BLOCKED, "REFERENCE_IN_USE");
        } else {
            if (result.status() != ProviderRefundResult.Status.ERROR) {
                refund.correlate(attempt.lookupReference());
                refund.clearAttention();
            }
            state.complete(attempt, result);
            audit.finish(
                    switch (result.status()) {
                        case SUCCEEDED -> Outcome.COMPLETED;
                        case FAILED -> Outcome.FAILED;
                        case PENDING -> Outcome.PENDING;
                        case ERROR -> Outcome.UNRESOLVED;
                    },
                    result.status() == ProviderRefundResult.Status.ERROR
                            ? "PROVIDER_UNRESOLVED"
                            : null);
        }
        return result(refund, audit);
    }

    @Transactional(readOnly = true)
    public Result read(UUID paymentId, UUID refundId, UUID auditId) {
        Refund refund = state.read(paymentId, refundId);
        var audit = audits.findById(auditId).orElseThrow();
        if (!audit.getRefundId().equals(refundId))
            throw new IllegalArgumentException("Audit refund mismatch");
        return result(refund, audit);
    }

    @Transactional(readOnly = true)
    public List<AuditView> history(UUID paymentId, UUID refundId) {
        state.read(paymentId, refundId);
        return audits.findTop20ByRefund_IdOrderByCreatedAtDescIdDesc(refundId).stream()
                .limit(20)
                .map(AuditView::from)
                .toList();
    }

    private Payment lock(UUID id) {
        return payments.lockForRefund(id)
                .orElseThrow(
                        () ->
                                new com.example.omp.payment.error.PaymentNotFoundException(
                                        "Payment not found"));
    }

    private boolean referenceInUse(UUID paymentId, UUID refundId, String reference) {
        return refunds.findByPaymentIdAndProviderReference(paymentId, reference)
                .filter(existing -> !existing.getId().equals(refundId))
                .isPresent();
    }

    private static boolean canApply(Payment payment, Refund refund) {
        return payment.getStatus().isRefundable()
                && payment.getProviderReference() != null
                && !payment.getProviderReference().isBlank()
                && payment.getCurrency().equals(refund.getCurrency())
                && refund.getAmount()
                                .getAmount()
                                .compareTo(payment.getRemainingRefundable().getAmount())
                        <= 0;
    }

    private static Result result(Refund refund, RefundReconciliationAudit audit) {
        return new Result(refund, AuditView.from(audit));
    }
}
