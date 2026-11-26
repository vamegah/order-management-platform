-- These queries inspect durable state after faults, restore and automatic recovery.
-- No payload, address, bank account or provider credential is returned.
SELECT 'missing_reservation' AS invariant,count(*) AS violations
FROM orders.order_line l JOIN orders.purchase_order o ON o.id=l.order_id
WHERE o.status NOT IN ('DRAFT','CANCELLED') AND l.reservation_id IS NULL
AND NOT (o.status='DELIVERED' AND EXISTS (
    SELECT 1 FROM orders.terminal_reservation_reference_disposition d
    JOIN orders.legacy_lifecycle_resolution r ON r.order_id=d.order_id
    WHERE d.order_id=o.id AND d.line_id=l.id AND r.resolved_at IS NOT NULL
    AND r.resolution='AUDITED_TERMINAL_DISPOSITION'));
SELECT 'missing_payment' AS invariant,count(*) AS violations
FROM orders.purchase_order o WHERE payment_id IS NULL AND status NOT IN ('DRAFT','CANCELLED')
AND NOT (o.status='DELIVERED' AND EXISTS (
    SELECT 1 FROM orders.legacy_lifecycle_resolution r WHERE r.order_id=o.id
    AND r.resolved_at IS NOT NULL AND r.resolution='AUDITED_TERMINAL_DISPOSITION'));
SELECT 'duplicate_reservation' AS invariant,count(*) AS violations
FROM (SELECT reservation_id FROM orders.order_line WHERE reservation_id IS NOT NULL GROUP BY reservation_id HAVING count(*)>1) duplicates;
SELECT 'negative_stock' AS invariant,count(*) AS violations
FROM inventory.stock_level WHERE quantity_on_hand < 0 OR quantity_reserved < 0 OR quantity_reserved > quantity_on_hand;
-- Additional service-specific money/reference reconciliation is executed by the lifecycle and payment acceptance suites.
