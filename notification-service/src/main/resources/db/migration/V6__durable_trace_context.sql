-- Additive metadata is retained with the immutable source payload for delayed retries.
-- No credentials, baggage, request body or user claims are copied into telemetry.
ALTER TABLE notifications.webhook_event_payload ADD COLUMN traceparent varchar(55);
ALTER TABLE notifications.webhook_event_payload ADD CONSTRAINT webhook_traceparent_format
    CHECK (traceparent IS NULL OR traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$');
