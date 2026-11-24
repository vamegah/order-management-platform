ALTER TABLE notifications.webhook_subscription ADD COLUMN previous_secret text,
    ADD COLUMN previous_secret_valid_until timestamptz, ADD COLUMN key_version integer NOT NULL DEFAULT 1;
CREATE TABLE notifications.webhook_event_payload(event_id varchar(100) PRIMARY KEY,event_type varchar(100) NOT NULL,payload jsonb NOT NULL,occurred_at timestamptz NOT NULL);
CREATE TABLE notifications.webhook_delivery_receipt(delivery_id uuid PRIMARY KEY,subscription_id uuid NOT NULL,event_id varchar(100) NOT NULL,completed_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE notifications.webhook_replay_audit(id bigserial PRIMARY KEY,delivery_id uuid NOT NULL,actor varchar(255) NOT NULL,payload_hash varchar(64) NOT NULL,replayed_at timestamptz NOT NULL DEFAULT now());
