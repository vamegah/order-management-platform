-- Order 302 is the bundled, synthetic CONFIRMED seed payment. Its test-only
-- provider reference is not an external charge and is handled by the noop provider.
-- Leave every other historical payment with missing provider provenance untouched.
UPDATE payments.payment
SET provider = 'noop'
WHERE id = '00000000-0000-0000-0000-000000000802'
  AND order_id = '00000000-0000-0000-0000-000000000302'
  AND idempotency_key = 'seed-order-302'
  AND provider_reference = 'pi_test_order302'
  AND status = 'CONFIRMED'
  AND provider IS NULL;
