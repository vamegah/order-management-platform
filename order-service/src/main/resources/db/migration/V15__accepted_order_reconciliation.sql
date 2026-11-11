ALTER TABLE orders.purchase_order DROP CONSTRAINT order_status_valid;
ALTER TABLE orders.purchase_order ADD CONSTRAINT order_status_valid
    CHECK (status IN ('DRAFT','PLACED','CONFIRMED','FULFILLING','CANCELLING','SHIPPED','DELIVERED','CANCELLED'));
ALTER TABLE orders.order_status_history DROP CONSTRAINT order_status_history_from_valid;
ALTER TABLE orders.order_status_history ADD CONSTRAINT order_status_history_from_valid
    CHECK (from_status IS NULL OR from_status IN
        ('DRAFT','PLACED','CONFIRMED','FULFILLING','CANCELLING','SHIPPED','DELIVERED','CANCELLED'));
ALTER TABLE orders.order_status_history DROP CONSTRAINT order_status_history_to_valid;
ALTER TABLE orders.order_status_history ADD CONSTRAINT order_status_history_to_valid
    CHECK (to_status IN ('DRAFT','PLACED','CONFIRMED','FULFILLING','CANCELLING','SHIPPED','DELIVERED','CANCELLED'));

CREATE TABLE orders.lifecycle_watch (
    order_id uuid PRIMARY KEY REFERENCES orders.purchase_order(id) DEFERRABLE INITIALLY DEFERRED,
    next_scan_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_id uuid,
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    attention boolean NOT NULL DEFAULT false,
    compensation_ready boolean NOT NULL DEFAULT false,
    last_error varchar(200),
    requested_by varchar(100) NOT NULL DEFAULT 'system:lifecycle',
    reason varchar(500) NOT NULL DEFAULT 'Accepted-order reconciliation',
    last_success_at timestamptz,
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);
CREATE INDEX idx_lifecycle_watch_due ON orders.lifecycle_watch(next_scan_at, order_id);
ALTER TABLE orders.fulfillment_task ADD COLUMN warehouse_id varchar(64);
UPDATE orders.fulfillment_task t SET warehouse_id = l.reservation_warehouse_id
    FROM orders.order_line l WHERE l.reservation_id = t.reservation_id AND l.order_id = t.order_id;
INSERT INTO orders.lifecycle_watch(order_id) SELECT id FROM orders.purchase_order;

-- Freeze pre-upgrade identities. Runtime resolution first verifies the complete
-- inventory lookup. The maintainer authorized audited cancellation only when
-- original active references cannot be recovered; this migration invents none.
CREATE TABLE orders.legacy_lifecycle_resolution (
    order_id uuid PRIMARY KEY REFERENCES orders.purchase_order(id),
    original_status varchar(20) NOT NULL,
    original_version bigint NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    resolved_at timestamptz,
    resolution varchar(50),
    evidence varchar(2000)
);
INSERT INTO orders.legacy_lifecycle_resolution(order_id, original_status, original_version)
    SELECT id, status, version FROM orders.purchase_order;
CREATE TABLE orders.lifecycle_reference_audit (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders.purchase_order(id),
    line_id uuid NOT NULL REFERENCES orders.order_line(id),
    previous_reservation_id uuid,
    verified_reservation_id uuid,
    observed_warehouse varchar(64),
    actor varchar(100) NOT NULL,
    reason varchar(500) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE orders.lifecycle_retry_audit (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders.purchase_order(id),
    actor varchar(100) NOT NULL,
    reason varchar(500) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
