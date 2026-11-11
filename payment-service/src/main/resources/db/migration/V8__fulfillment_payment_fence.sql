-- The payment row lock serializes these decisions with refund admission.
-- Fences are durable across worker death; an expired RPC deadline never unlocks money.
ALTER TABLE payments.payment
    ADD COLUMN fulfillment_state varchar(16) NOT NULL DEFAULT 'OPEN'
        CHECK (fulfillment_state IN ('OPEN', 'FULFILLING', 'SHIPPED', 'CANCELLING', 'REFUNDABLE'));
