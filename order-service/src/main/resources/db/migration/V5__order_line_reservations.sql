-- Nullable for orders accepted before reservation references were persisted.
ALTER TABLE orders.order_line ADD COLUMN reservation_id uuid;
CREATE INDEX idx_order_line_reservation ON orders.order_line(reservation_id)
    WHERE reservation_id IS NOT NULL;
