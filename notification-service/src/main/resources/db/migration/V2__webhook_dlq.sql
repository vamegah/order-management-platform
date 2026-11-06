-- V2__webhook_dlq.sql
-- The DLQ table is created in V1 for fresh installs.
-- This migration exists only to keep the version history explicit; it is a no-op
-- if webhook_delivery_dlq already exists.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'notifications'
          AND table_name = 'webhook_delivery_dlq'
    ) THEN
        CREATE TABLE notifications.webhook_delivery_dlq (
            id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            delivery_id         uuid NOT NULL,
            subscription_id     uuid NOT NULL,
            event_id            varchar(100) NOT NULL,
            event_type          varchar(100) NOT NULL,
            payload             jsonb NOT NULL,
            reason              varchar(200) NOT NULL,
            final_status        varchar(20) NOT NULL,
            attempts            integer NOT NULL,
            received_at         timestamptz NOT NULL DEFAULT now(),
            replayed_at         timestamptz,
            replayed_by         varchar(100),
            CONSTRAINT chk_dlq_final_status
                CHECK (final_status IN ('FAILED_RETRYABLE', 'FAILED_PERMANENT'))
        );
        CREATE INDEX idx_dlq_delivery ON notifications.webhook_delivery_dlq(delivery_id);
        CREATE INDEX idx_dlq_received ON notifications.webhook_delivery_dlq(received_at DESC);
        CREATE INDEX idx_dlq_unreplayed
            ON notifications.webhook_delivery_dlq(received_at)
            WHERE replayed_at IS NULL;
    END IF;
END
$$;