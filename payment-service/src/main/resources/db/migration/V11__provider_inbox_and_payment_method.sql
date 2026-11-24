ALTER TABLE payments.payment ADD COLUMN payment_method_id varchar(255),
    ADD COLUMN action_required boolean NOT NULL DEFAULT false,
    ADD COLUMN charge_started_at timestamptz, ADD COLUMN cancellation_requested boolean NOT NULL DEFAULT false;
ALTER TABLE payments.provider_event ADD COLUMN payment_ref varchar(255),
    ADD COLUMN body_sha256 varchar(64), ADD COLUMN attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN error_code varchar(50),
    ADD COLUMN processed_at timestamptz;
CREATE INDEX provider_inbox_due ON payments.provider_event(next_attempt_at,created_at) WHERE status='RECEIVED';
CREATE TABLE payments.provider_event_replay_audit(
    id bigserial PRIMARY KEY, event_id uuid NOT NULL REFERENCES payments.provider_event(id),
    actor varchar(255) NOT NULL, reason varchar(500) NOT NULL, replayed_at timestamptz NOT NULL DEFAULT now());

UPDATE payments.provider_event SET payment_ref=CASE WHEN payload IS JSON OBJECT THEN CASE WHEN event_type LIKE 'refund.%' THEN payload::jsonb->>'payment_intent' ELSE payload::jsonb->>'id' END ELSE NULL END WHERE payment_ref IS NULL;
UPDATE payments.provider_event SET status='ATTENTION',error_code='LEGACY_UNRESOLVED' WHERE status='FAILED';
UPDATE payments.provider_event SET processed_at=created_at WHERE status IN('PROCESSED','IGNORED');
