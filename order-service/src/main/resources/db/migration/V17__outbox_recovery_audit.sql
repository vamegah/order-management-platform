CREATE TABLE outbox_recovery_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), outbox_id uuid NOT NULL,
    actor varchar(255) NOT NULL DEFAULT session_user, reason varchar(500) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CHECK(length(trim(reason)) BETWEEN 1 AND 500)
);

ALTER TABLE outbox_event ADD COLUMN sequence_id bigserial NOT NULL;
CREATE UNIQUE INDEX outbox_sequence ON outbox_event(sequence_id);
CREATE INDEX outbox_aggregate_pending ON outbox_event(topic,aggregate_id,sequence_id) WHERE status IN ('PENDING','FAILED');
