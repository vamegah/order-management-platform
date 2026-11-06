ALTER TABLE orders.purchase_order
    ADD COLUMN payment_id uuid;

COMMENT ON COLUMN orders.purchase_order.payment_id IS
    'Reference to the payment intent created at order placement. Set on create.';