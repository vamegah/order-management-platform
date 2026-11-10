# Core order lifecycle

Checkout commits the aggregate, verified line references, warehouse snapshots,
acceptance journal, lifecycle watch and outbox event in one database transaction.
Public order lines remain immutable. Inventory and payment RPCs run outside order
transactions. All reconciliation admissions use a current, unexpired database lease.

## Durable decisions

Payment serializes refund admission and fulfillment decisions using the same locked
payment row. Its decision progresses from `OPEN` to `FULFILLING`, then `SHIPPED`, or
to `CANCELLING`, then `REFUNDABLE`. The decision survives process restarts and has
no timeout. A worker lease expiring never authorizes a refund or reverses shipment.
Pending refunds and payments that are not fully confirmed prevent fulfillment.
`SHIPPED` prevents new refunds; post-shipment returns need a separate business flow.

Inventory serializes reserve, commit, release and compensation for an order with
a transaction-scoped advisory lock. Compensation releases active holds and
restocks committed quantities once, atomically with reservation state and outbox
events. A permanent order tombstone rejects delayed reserve/commit calls. Replays
return `RELEASED`, `EXPIRED` or `RESTOCKED` without another stock movement.
Commit checks the database TTL itself, including before the next scheduler tick.

The coordinator validates both complete, bounded discovery results before changing
remote decisions. It records payment cancellation decisions, compensates inventory,
permits refunds and admits cleanup tasks in that order. Accepted-order cleanup
cannot run before this admission. Orphan checkout compensation retains its existing
journal flow. Completed cancellations remain discoverable periodically so a delayed
payment creation cannot escape cleanup.

## Transition matrix

| Order and operation | Payment | Inventory | Result |
| --- | --- | --- | --- |
| Checkout | Matching pending/confirmed intent | Distinct verified active holds | DRAFT, atomic references/watch/outbox |
| DRAFT place | Accepted intent remains linked | Verified references required | PLACED |
| PLACED confirm | Matching fully confirmed payment | Verified references required | CONFIRMED |
| CONFIRMED ship | Full payment check, durable BEGIN decision | Each owned hold committed and acknowledged | FULFILLING/202 until completion |
| FULFILLING complete | Durable SHIPPED decision | All task acknowledgments durable | SHIPPED, one history/outbox event |
| DRAFT/PLACED/CONFIRMED cancel | Pending cancel or remaining refund queued | Release/compensation durable | CANCELLED, cleanup tracked |
| FULFILLING cancel or required hold unavailable | CANCEL competes atomically with SHIP | Compensate only if cancellation wins | CANCELLING/202 |
| CANCELLING, cancellation won | REFUNDABLE after stock compensation | All owned holds released/expired/restocked | CANCELLED, durable refund cleanup |
| CANCELLING, shipment already won | SHIPPED | Completed shipment tasks required | SHIPPED; no compensation/refund |
| Accepted order loses inventory/payment | Authority checked by scheduled worker | Expired/released/unusable hold | Audited cancellation and cleanup |
| SHIPPED deliver | Existing shipment decision retained | Committed stock retained | DELIVERED |
| SHIPPED/DELIVERED cancel | Existing shipment retained | No stock mutation | 409 |
| Duplicate cancel/ship/compensation | Original decisions retained | No duplicate movement | Original result or pending 202 |
| Foreign/malformed/unbounded discovery or stale worker | No new task admission | No partial admission | Retry/attention; original state retained |

REST returns the existing problem envelope for rejected transitions. Shipment and
cancellation return 202 with `Location` and `Retry-After: 60` while the order is
`FULFILLING` or `CANCELLING`. GraphQL exposes the same status enum. The additive
gRPC methods preserve existing field numbers and enum values. Invalid requests,
authorization failures, conflicts and contention map to `INVALID_ARGUMENT`,
`PERMISSION_DENIED`, `FAILED_PRECONDITION` and `ABORTED`, respectively.

## Historical policy and final integrity

V15/V16 capture existing order/line identities before reconciliation. The worker
recovers only unique original holds with matching order, SKU, quantity and any
known warehouse. Unknown original warehouse provenance stays unknown; an observed
warehouse belongs in the audit and cannot become an invented historical snapshot.

The maintainer approved audited terminal exceptions and, on November 10, 2026,
audited cancellation of irrecoverable active legacy orders. The worker preserves
original identities/status/version, records verified or rejected pointers, creates
the terminal disposition and durably compensates owned inventory/payments. It
does not allocate replacement stock. An already physical shipment with inconsistent
ledger evidence requires operator reconciliation and remains visible as attention.
Existing terminal attestations retain their original actor and evidence on retry.

Future/resolved active orders require 1–100 verified, non-null line references at
transaction commit. A database claim makes a reservation unique across all verified
lines, including competing transactions. Deferred triggers allow atomic checkout
and repair while rejecting incomplete final state. Only captured historical
DELIVERED/CANCELLED lines with an explicit audit may lack a reference.

The upgrade census must have no unresolved historical rows or unverified active
lines before an installation declares its historical migration complete. Inspect
`orders.legacy_lifecycle_resolution`, `orders.lifecycle_reference_audit` and
`orders.terminal_reservation_reference_disposition`; preserve these ledgers.

## Deployment and operations

Provision the private UID/GID 1000 credential/projection paths using the existing
[supervised recovery deployment procedure](order-recovery-identity.md), then add
`docker-compose.lifecycle.yml` and `docker-compose.recovery-projector.yml` to the
base deployment. Deploy the matching order, inventory, payment and GraphQL
artifacts together so their additive RPC/status contracts and migrations agree.
The lifecycle overlay enables accepted-order reconciliation and
30-minute inventory expiry. The existing recovery overlay also enables lifecycle
reconciliation when deploying orphan checkout recovery. Use both supervised
workers for the complete lifecycle deployment. Never enable the short TTL in
`docker-compose.lifecycle-acceptance.yml` on a real installation.

The worker reads the projected `order.reconcile` token on every tick and the
cancellation fast path reads it for each request. Missing/invalid projection does
not claim or mutate work. The projector renews tokens atomically; existing JWT,
issuer, mTLS and private file checks remain authoritative. Public user tokens do
not gain reconciliation scope. Without a configured workload identity, cancellation
intent remains durable for the supervised worker rather than bypassing its fences.

Default reconciliation batches contain at most ten orders, each with a two-minute
database lease. Active scans run every 30 seconds; incomplete cleanup retries after
one minute; completed cancellations rescan after five minutes. No RPC runs while a
lease transaction holds database locks. Bounded failures expose attention after
three consecutive failed scans; a successful scan resets this count.

Administrators inspect `GET /api/v1/order-lifecycle/{id}` and request
`POST /api/v1/order-lifecycle/{id}/retry` with a nonblank reason of at most 500
characters. Retry preserves current leases and records the verified JWT subject,
reason and timestamp. It changes scheduling, never payment, stock or order status.
Existing owner/admin order endpoints keep their authorization policy. Metrics
include `order.lifecycle.attention`, `order.lifecycle.lag.seconds`, scans/failures,
worker credential failures and the existing cleanup/fulfillment/expiry counters.
Logs retain resource/order correlation and exception types without credentials.

## Verification

`CoreOrderLifecycleIT`, `InventoryCompensationIT` and `PaymentFulfillmentFenceIT`
exercise actual PostgreSQL migrations, deferred constraints, shared-reference races,
worker fencing, outbox rollback, stock conservation and payment decision races.
HTTP tests cover administrator-only inspection/retry and verified actor auditing.

The isolated `lifecycle-e2e` Maven profile runs `CoreLifecycleAcceptanceIT` against
independent service containers with the real issuer, mTLS and supervised scheduler.
It covers natural accepted-order expiry, partial stock commits during an inventory
restart, refund/shipment races and the historical census. The CI evidence checker
requires every selected suite and rejects failures, errors, skips and missing reports.
