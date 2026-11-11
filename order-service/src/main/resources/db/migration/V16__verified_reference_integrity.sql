ALTER TABLE orders.order_line ADD COLUMN reservation_verified boolean NOT NULL DEFAULT false;

CREATE TABLE orders.legacy_lifecycle_line (
    line_id uuid PRIMARY KEY REFERENCES orders.order_line(id),
    order_id uuid NOT NULL REFERENCES orders.legacy_lifecycle_resolution(order_id),
    product_id uuid NOT NULL,
    sku varchar(64) NOT NULL,
    quantity integer NOT NULL,
    original_reservation_id uuid,
    original_warehouse varchar(64)
);
INSERT INTO orders.legacy_lifecycle_line
    SELECT id, order_id, product_id, product_sku, quantity, reservation_id, reservation_warehouse_id
    FROM orders.order_line;

-- Only verified references obtain a claim. The primary key also serializes
-- competing transactions trying to attach one remote hold to different lines.
CREATE TABLE orders.reservation_reference_claim (
    reservation_id uuid PRIMARY KEY,
    line_id uuid NOT NULL UNIQUE REFERENCES orders.order_line(id) ON DELETE CASCADE,
    order_id uuid NOT NULL REFERENCES orders.purchase_order(id)
);
CREATE FUNCTION orders.claim_verified_reservation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        DELETE FROM orders.reservation_reference_claim WHERE line_id = OLD.id
            AND (TG_OP = 'DELETE' OR NEW.reservation_id IS DISTINCT FROM OLD.reservation_id
                OR NOT NEW.reservation_verified);
    END IF;
    IF TG_OP <> 'DELETE' AND NEW.reservation_verified THEN
        IF NEW.reservation_id IS NULL THEN RAISE EXCEPTION 'Verified reference cannot be NULL'; END IF;
        INSERT INTO orders.reservation_reference_claim(reservation_id, line_id, order_id)
            VALUES (NEW.reservation_id, NEW.id, NEW.order_id) ON CONFLICT DO NOTHING;
        IF NOT EXISTS (SELECT 1 FROM orders.reservation_reference_claim
            WHERE reservation_id = NEW.reservation_id AND line_id = NEW.id AND order_id = NEW.order_id)
            THEN RAISE EXCEPTION 'Reservation is already bound to another line'; END IF;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER claim_verified_reservation AFTER INSERT OR UPDATE OR DELETE
    ON orders.order_line FOR EACH ROW EXECUTE FUNCTION orders.claim_verified_reservation();

CREATE FUNCTION orders.check_order_reference_integrity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; current_status varchar(20); legacy boolean; total integer;
BEGIN
    IF TG_TABLE_NAME = 'purchase_order' THEN target := COALESCE(NEW.id, OLD.id);
    ELSE target := COALESCE(NEW.order_id, OLD.order_id); END IF;
    SELECT status INTO current_status FROM orders.purchase_order WHERE id = target;
    IF NOT FOUND THEN RETURN NULL; END IF;
    -- Existing rows retain their original state until the bounded upgrade worker
    -- verifies them or applies the explicitly approved audited cancellation policy.
    IF EXISTS (SELECT 1 FROM orders.legacy_lifecycle_resolution
        WHERE order_id = target AND resolved_at IS NULL) THEN RETURN NULL; END IF;
    SELECT EXISTS (SELECT 1 FROM orders.legacy_lifecycle_resolution WHERE order_id = target) INTO legacy;
    SELECT count(*) INTO total FROM orders.order_line WHERE order_id = target;
    IF total > 100 OR (total = 0 AND NOT (legacy AND current_status IN ('DELIVERED','CANCELLED')))
        THEN RAISE EXCEPTION 'Order requires 1..100 verified lines'; END IF;
    IF EXISTS (SELECT 1 FROM orders.order_line l WHERE l.order_id = target AND
        (l.reservation_id IS NULL OR NOT l.reservation_verified) AND NOT
        (legacy AND current_status IN ('DELIVERED','CANCELLED') AND l.reservation_id IS NULL
         AND EXISTS (SELECT 1 FROM orders.terminal_reservation_reference_disposition d
                     WHERE d.line_id = l.id AND d.order_id = target)))
        THEN RAISE EXCEPTION 'Unverified order reservation reference'; END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER enforce_order_reference_integrity
    AFTER INSERT OR UPDATE OR DELETE ON orders.order_line DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION orders.check_order_reference_integrity();
CREATE CONSTRAINT TRIGGER enforce_parent_reference_integrity
    AFTER INSERT OR UPDATE ON orders.purchase_order DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION orders.check_order_reference_integrity();
CREATE CONSTRAINT TRIGGER enforce_resolved_reference_integrity
    AFTER UPDATE ON orders.legacy_lifecycle_resolution DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION orders.check_order_reference_integrity();
