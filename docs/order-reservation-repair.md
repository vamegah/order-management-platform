# Historical reservation reference repair

Historical order lines can have a NULL reservation reference. Flyway V11 adds
an audit table; it does not invent reservation IDs or change historical rows.
Administrators can restore a missing pointer to a verified existing hold through
`/api/v1/order-reservation-repairs/{orderId}`. The operation neither creates nor
mutates inventory and does not advance the order state.

## Inspect and prepare

### Census missing and shared references

An administrator can scan local reference metadata with
`GET /api/v1/order-reservation-repairs/census?limit=50`. Responses contain
`scanned`, `nextAfterLineId` and `findings`. Supply the returned cursor as
`afterLineId` on the next request until `nextAfterLineId` is null. An empty
`findings` list does not end the traversal: a page can contain healthy lines.
The limit must be between 1 and 100. Each query limits its result to `limit + 1`
line rows in UUID order, with a five-second database transaction timeout.

Findings identify the order, its version and status, line, SKU, quantity and
reference, with `MISSING_REFERENCE` or `SHARED_REFERENCE`. A shared reference
appears on every affected line, including lines on different orders. The scan
does not call inventory, allocate holds, change order state, append repair audit
rows or publish events. Inspect and verify each finding through the repair
procedure below before proposing a binding.

This is a live metadata census, not a consistent database snapshot or proof of
remote ownership. Concurrent inserts behind the cursor or repairs can change
results; repeat the scan or use an operationally consistent snapshot for a final
historical completeness decision. Empty orders and wrong remote ownership on an
otherwise distinct reference require separate validation. Terminal missing rows,
warehouse provenance, active holds that cannot be recovered and conflicting
existing references still require an audited historical disposition. They are
not silently treated as repaired.

### Inspect an individual order

Use an administrator identity accepted by order-service and inventory-service.
GET the repair resource to obtain its current `version`, status, line IDs and
`missing` count. Ordinary order owners cannot inspect or execute repairs.

Identify the original reservation for every line from trusted inventory records.
Do not allocate replacement inventory or infer a reservation UUID from a SKU.
The request must include every line, including references already present, with
distinct existing reservation IDs. An existing reference cannot be replaced.
Requests contain at most 100 bindings and a nonblank reason of at most 500
characters. The verified operator subject, rather than a caller-supplied actor,
is recorded in the audit.

POST the same resource with the inspected version, an operational reason and
the complete binding list, for example:

```json
{
  "expectedVersion": 3,
  "reason": "Restore original hold verified in inventory; incident INC-123",
  "bindings": [
    {
      "lineId": "11111111-1111-4111-8111-111111111111",
      "reservationId": "22222222-2222-4222-8222-222222222222"
    }
  ]
}
```

For an actual restoration, the order must be DRAFT, PLACED or CONFIRMED. Each
inventory lookup must return the requested reservation ID, this order ID, the
line's SKU and quantity, and ACTIVE state. Historical lines do not retain their
original warehouse, so this endpoint cannot establish warehouse provenance.
Missing, inactive or unverifiable resources require separate investigation;
this endpoint cannot reconstruct lost holds or repair fulfilled/cancelled orders.

## Commit, retry and audit

Inventory reads run before the local write transaction. The transaction locks
the order, rechecks its version, state and complete binding plan, restores only
NULL references, appends one audit row per restored line and increments the
order version once. A failure rolls back all these writes. The version change
also rejects an order transition that loaded the previous aggregate version.
Order status, business history and public events are unchanged by this metadata
repair. Ordinary later transitions retain their existing outbox behavior.

A successful response reports the new version, `missing: 0` and the number
`restored`. Repeating an already matching complete plan returns `restored: 0`
without another inventory call, audit row or version increment, even if the
submitted version is stale. This makes retry after a lost response safe.
Conflicting replacements remain rejected. After an HTTP 409 caused by another
order change, reload and review its current state before submitting a new plan.
Malformed or mismatched plans return 400, and unavailable or invalid downstream
verification returns a generic 502. No partial plan is committed.

Read the durable audit with the incident's order UUID:

```sql
SELECT order_id, line_id, reservation_id, previous_order_version,
       actor, reason, repaired_at
FROM orders.reservation_reference_repair
WHERE order_id = :order_id
ORDER BY repaired_at, id
LIMIT 100;
```

Inventory state can change after a lookup. This operation restores a reference;
it cannot coordinate expiry or fulfillment across services. Fulfillment still
checks current reservation/payment state. Recovery and expiry workers remain
disabled by default. Deployed workload identity, historical inventory discovery,
process-crash recovery and accepted-order/payment expiry coordination retain
their separate acceptance requirements in [TASKS.md](../TASKS.md).

## Census verification

Clean order/shared verification passes 505 cases (417 unit and 88 integration),
including complete cursor traversal, missing/shared references, read-only state,
administrator authorization and invalid request boundaries. The unchanged order
coverage gate passes at 86.30%; all five focused unit comparisons and six complete
static reports pass. Source-bound local evidence is retained in
`coverage/reservation-reference-census-local-evidence.zip` and JSON. Complete CI
must execute 1,207 reactor and 25 deployed cases before acceptance. The census
does not close historical repair/disposition or remote ownership requirements.
