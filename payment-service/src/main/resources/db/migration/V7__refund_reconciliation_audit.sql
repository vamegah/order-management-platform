-- Never silently deduplicate financial history. Existing duplicate bindings must
-- be investigated before this constraint can be installed.
CREATE UNIQUE INDEX ux_refund_provider_reference
    ON payments.refund (payment_id, provider_reference)
    WHERE provider_reference IS NOT NULL;

CREATE TABLE payments.refund_reconciliation_audit (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL REFERENCES payments.refund(id),
    candidate_reference varchar(255) NOT NULL,
    actor_subject varchar(255) NOT NULL,
    actor_issuer varchar(512),
    reason varchar(500) NOT NULL,
    outcome varchar(32) NOT NULL,
    detail_code varchar(64),
    lease_id uuid,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    CHECK (outcome IN ('IN_PROGRESS', 'BUSY', 'BLOCKED', 'NO_CHANGE',
                      'UNRESOLVED', 'PENDING', 'COMPLETED', 'FAILED', 'SUPERSEDED')),
    CHECK ((outcome = 'IN_PROGRESS') = (completed_at IS NULL))
);
CREATE INDEX idx_refund_reconciliation_history
    ON payments.refund_reconciliation_audit (refund_id, created_at DESC, id DESC);
