CREATE TABLE domain_event_inbox (
    consumer_id varchar(32) NOT NULL, event_id uuid NOT NULL, payload_hash varchar(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (consumer_id, event_id)
);
CREATE TABLE notification_dispatch (
    id uuid PRIMARY KEY, transport varchar(8) NOT NULL CHECK (transport IN ('KAFKA','REDIS')),
    destination varchar(255) NOT NULL, message_key varchar(255) NOT NULL, payload text NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','PUBLISHED','FAILED')),
    attempts integer NOT NULL DEFAULT 0, available_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz, last_error varchar(64)
);
CREATE INDEX notification_dispatch_pending ON notification_dispatch(available_at,created_at) WHERE state='PENDING';
CREATE TABLE domain_event_quarantine (
    sequence_id bigserial UNIQUE NOT NULL,
    id uuid PRIMARY KEY, consumer_id varchar(32) NOT NULL, source_topic varchar(255) NOT NULL,
    source_partition integer NOT NULL, source_offset bigint NOT NULL, payload text NOT NULL,
    payload_hash varchar(64) NOT NULL, failure_code varchar(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(), replayed_at timestamptz, replayed_by varchar(255),
    UNIQUE(consumer_id,source_topic,source_partition,source_offset)
);
CREATE TABLE event_recovery_audit (
    id uuid PRIMARY KEY, resource_id uuid NOT NULL, operation varchar(32) NOT NULL,
    actor varchar(255) NOT NULL, reason varchar(500) NOT NULL, payload_hash varchar(64),
    recorded_at timestamptz NOT NULL DEFAULT now()
);
