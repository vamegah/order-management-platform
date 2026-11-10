-- Preserve original warehouse intent for new checkouts. Historical rows stay unknown;
-- neither current stock nor a remote hold establishes their original warehouse.
ALTER TABLE orders.order_line ADD COLUMN reservation_warehouse_id varchar(64);
ALTER TABLE orders.order_line ADD CONSTRAINT ck_order_line_reservation_warehouse
    CHECK (reservation_warehouse_id IS NULL OR
           (length(reservation_warehouse_id) > 0 AND
            reservation_warehouse_id !~ '^[[:space:]]|[[:space:]]$'));
