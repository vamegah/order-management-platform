ALTER TABLE inventory.reservation DROP CONSTRAINT chk_reservation_status;
ALTER TABLE inventory.reservation ADD CONSTRAINT chk_reservation_status
    CHECK (status IN ('ACTIVE', 'COMMITTED', 'RELEASED', 'EXPIRED', 'RESTOCKED'));

-- Retained indefinitely: unknown delayed reserve/commit requests must remain fenced.
CREATE TABLE inventory.order_compensation (
    order_id uuid PRIMARY KEY,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
