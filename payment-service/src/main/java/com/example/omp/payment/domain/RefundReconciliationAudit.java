package com.example.omp.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Immutable request evidence with a transactionally updated reconciliation outcome. */
@Entity
@Table(name = "refund_reconciliation_audit", schema = "payments")
public class RefundReconciliationAudit {
    public enum Outcome {
        IN_PROGRESS,
        BUSY,
        BLOCKED,
        NO_CHANGE,
        UNRESOLVED,
        PENDING,
        COMPLETED,
        FAILED,
        SUPERSEDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id", nullable = false, updatable = false)
    private Refund refund;

    @Column(name = "candidate_reference", nullable = false, length = 255, updatable = false)
    private String candidateReference;

    @Column(name = "actor_subject", nullable = false, length = 255, updatable = false)
    private String actorSubject;

    @Column(name = "actor_issuer", length = 512, updatable = false)
    private String actorIssuer;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Outcome outcome = Outcome.IN_PROGRESS;

    @Column(name = "detail_code", length = 64)
    private String detailCode;

    @Column(name = "lease_id")
    private UUID leaseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected RefundReconciliationAudit() {}

    public RefundReconciliationAudit(
            Refund refund, String reference, String subject, String issuer, String reason) {
        this.refund = refund;
        this.candidateReference = reference;
        this.actorSubject = subject;
        this.actorIssuer = issuer;
        this.reason = reason;
    }

    public void bindLease(UUID lease) {
        this.leaseId = lease;
    }

    public void finish(Outcome outcome, String detail) {
        if (this.outcome != Outcome.IN_PROGRESS || outcome == Outcome.IN_PROGRESS)
            throw new IllegalStateException("Reconciliation audit is already final");
        this.outcome = outcome;
        this.detailCode = detail;
        this.completedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRefundId() {
        return refund.getId();
    }

    public String getCandidateReference() {
        return candidateReference;
    }

    public String getActorSubject() {
        return actorSubject;
    }

    public String getActorIssuer() {
        return actorIssuer;
    }

    public String getReason() {
        return reason;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getDetailCode() {
        return detailCode;
    }

    public UUID getLeaseId() {
        return leaseId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
