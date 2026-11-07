# Admitting historical orphans to checkout recovery

Historical inventory/payment effects can predate the checkout journal. For a
known, reviewed orphan order UUID, an administrator can create a durable recovery
admission with an operator audit. The action cannot adopt any existing order or
reclassify a normal checkout attempt. It creates no order, inventory hold,
payment or cleanup task and makes no remote call.

Deploy journal-first checkout on every writer and verify the scoped
[recovery identity](order-recovery-identity.md) before using this operation.
Discovery and cleanup still require the opt-in worker; the base topology keeps
it disabled. Older writers that mutate remote resources without starting the
journal do not participate in the admission/checkout fence.

## Review candidates

Use trusted inventory/payment records to identify the original order UUID. In
the reference deployment's shared PostgreSQL database, a read-only operator
account with access to all three schemas can inspect bounded candidates:

```sql
WITH resources AS (
    SELECT 'RESERVATION' AS resource_type, id AS resource_id, order_id,
           status, created_at FROM inventory.reservation
    WHERE created_at < now() - interval '1 day'
    UNION ALL
    SELECT 'PAYMENT', id, order_id, status, created_at FROM payments.payment
    WHERE created_at < now() - interval '1 day'
)
SELECT resource_type, resource_id, order_id, status, created_at
FROM resources r
WHERE NOT EXISTS (SELECT 1 FROM orders.purchase_order o WHERE o.id = r.order_id)
  AND NOT EXISTS (SELECT 1 FROM orders.checkout_attempt a WHERE a.order_id = r.order_id)
ORDER BY created_at, resource_type, resource_id
LIMIT 100;
```

This is a review queue, not an instruction to release or refund every row. Verify
that the UUID belongs to this order deployment and has no accepted aggregate or
active checkout. Separate databases require an equivalent reviewed ledger
comparison. This endpoint does not enumerate remote ledgers or invent missing
order identities. Existing cancelled orders retry their known cleanup tasks;
missing historical references still require separate investigation. Active and
fulfilled orders require their own lifecycle handling.

## Admit a reviewed UUID

```http
POST /api/v1/order-recovery/{orderId}/adopt
Authorization: Bearer <administrator JWT>
Content-Type: application/json

{"reason":"Reviewed historical orphan; incident INC-123"}
```

The reason must be nonblank and at most 500 characters. The audit uses the
authenticated operator subject, not a caller-supplied actor. The first response
reports `submitted: true` and journal `status: "FAILED"`: the historical checkout
is eligible for discovery. This status does not mean the admission request failed
or that remote cleanup completed.

The transaction inserts the journal entry and audit together. Recovery cannot
observe an unaudited admission. The unique journal identity serializes admission
with normal checkout start; both cannot claim the same UUID. Normal acceptance
also rejects a journal entry admitted to recovery. Audit failure rolls back the
new journal entry so a corrected request can retry.

A matching repeat reports `submitted: false`, the existing recovery status and
the first audit's operator, reason and timestamp. It does not replace that audit,
reset a lease or make a completed scan immediately due. HTTP 409 means an order
or non-adopted checkout attempt already exists; use its normal recovery process.
Validation returns 400, including malformed order UUIDs with the stable problem
detail `Invalid request parameter`. Missing authentication returns 401 and
non-administrator access returns 403 for a well-formed request.

## Observe discovery and cleanup

The existing worker discovers reservations/payments by the admitted order UUID,
validates each complete bounded result and queues only verified resource IDs.
Normal cleanup then validates current resource ownership/state before mutation.
An admission can remain pending if the issuer, downstream service, resource state
or refund outcome is unresolved. Repeated scans and cleanup use existing leases,
backoff and idempotency. The admission does not bypass those checks.

Inspect the first audit and current worker state with a read-only query:

```sql
SELECT a.order_id, a.actor, a.reason, a.requested_at,
       j.status, j.attempts, j.recover_after, j.lease_until, j.last_error
FROM orders.checkout_recovery_adoption a
JOIN orders.checkout_attempt j ON j.order_id = a.order_id
WHERE a.order_id = :order_id;
```

Use the metrics, task inspection and administrator retry described in
[order cleanup](order-cleanup.md). A successful scan or zero completed tasks does
not prove that every resource was compensated. Committed inventory and permanent
provider failures still require separate operator handling. Audits and journal
rows are retained; retention and archive policy remain separate work.

Flyway V12 adds the audit table without modifying historical rows. This local
implementation does not establish deployed identity, process-crash recovery,
legacy ledger completeness or accepted-order/payment expiry coordination. Those
acceptance items remain open in [TASKS.md](../TASKS.md).
