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

## Audited terminal exceptions

On 2026-11-09 the maintainer approved explicit audited exceptions for historical
DELIVERED/CANCELLED orders whose missing reservation cannot be recovered. Active
and future accepted orders retain their verified-reference requirement. This
operation records an administrator's investigation and attestation; it does not
prove remote absence, reconstruct a hold or settle inventory/payment cleanup.

Flyway V13 captures the missing lines present at upgrade, including their order,
product, SKU, quantity, original order version and status. Capture does not approve
an exception. A line must still match that captured identity, remain without a
reference, have no existing reference-repair audit, and now belong to a DELIVERED
or CANCELLED order. Newly created lines cannot claim the historical exception.
Existing conflicting references and unresolved active rows require repair or
separate investigation; they cannot be waived by this endpoint.

After checking trusted inventory records and incident evidence, an administrator
can inspect `GET /api/v1/order-reservation-repairs/{orderId}/terminal-dispositions`.
It returns the order version/status and each line's reference, eligibility and
existing audit. Orders with more than 100 lines require separate investigation.
GET is read-only and bounded to 101 joined rows with a five-second transaction.

POST that resource with every missing line exactly once, the inspected version,
a reason (1-500 characters) and an evidence description (1-2000 characters):

```json
{
  "expectedVersion": 0,
  "reason": "Original hold cannot be recovered after historical investigation",
  "evidence": "Incident INC-123: reviewed original inventory ledger and archive",
  "lineIds": ["00000000-0000-0000-0000-000000000401"]
}
```

Evidence is retained as text and never fetched as a URL. The verified subject is
recorded as actor; callers cannot submit a replacement actor or policy code.
The transaction locks the aggregate, checks complete eligibility and version,
inserts one audit per missing line and increments the order version once. Any
failure rolls back all audit/version writes. References, order status, payment,
status history and public outbox events are unchanged.

A response reports `recorded` and the resulting version and audits. Retrying the
same complete line plan with the same subject and stripped reason/evidence returns
`recorded: 0` and the original audits, even with a stale version after a lost
reply. Changed attestations conflict rather than overwrite evidence. Stale new
plans or ineligible/nonterminal rows return 409; malformed/partial plans return
400. Anonymous and ordinary owners cannot inspect or record dispositions.

The census continues reporting the physical missing reference after a disposition.
Consult the disposition resource to distinguish an approved historical exception
from an unresolved finding. Neither the eligibility ledger nor an unrecorded
exception is acceptance of an active order. Retain both eligibility and audit
tables with the historical order records.

Clean order/shared verification passes 548 cases: 452 unit and 96 integration,
including a real V3-to-current PostgreSQL upgrade that preserves historical rows,
status history and applied migration checksums. It verifies future/active rejection,
changed line identity, conflicting/stale attestations, concurrent lost-response
retries, second-line rollback and real HTTP administrator enforcement. The order
coverage gate passes at 87.13%; all five unit comparisons and six complete static
reports pass. Distinct OpenAPI model names preserve the existing repair contract.
Exact-source reports are retained in
`coverage/terminal-reservation-disposition-local-evidence.zip` and JSON.
The approved PR #18 merge `93df5e3` passes all twenty PR/main jobs, 1,250 reactor
and 25 deployed cases; the recovered main reports and preserved GitHub failures
are retained in `coverage/terminal-reservation-disposition-main-ci-evidence.zip`
and JSON. See [the acceptance record](task-implementation-report.md#terminal-policy-approved-merge-and-main-acceptance-2026-11-09).

## Forward transitions after investigation

Orders with empty, oversized, missing or internally shared reservation references
cannot move forward through placement, confirmation, shipment intent/completion
or delivery. The normal 409 problem preserves the failed command's durable rows
and events. Use the existing complete, verified repair plan for eligible missing
active pointers, reload the resulting version and retry the intended transition.
A successful repair does not itself place or confirm the order.

Cancellation remains available in DRAFT/PLACED/CONFIRMED through its normal
authorized action. It retains unresolved metadata rather than inventing a hold or
recording a terminal exception automatically. Complete remote investigation and
cleanup before an irrecoverability attestation; an accepted cancellation is not
proof that unknown remote resources do not exist. Existing non-NULL foreign or
conflicting pointers, warehouse provenance and final database integrity require
the remaining historical ownership work.
