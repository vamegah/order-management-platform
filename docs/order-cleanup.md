# Durable order events and cleanup

Order creation and status transitions record lifecycle events in `orders.outbox_event` within the aggregate transaction. The shared drainer publishes them after commit with at-least-once delivery. A Kafka outage no longer makes order creation fail after its database commit. Consumers must deduplicate events.

Cancellation inserts `orders.cleanup_task` rows in the same database transaction as CANCELLED state and its outbox event. Each known reservation and payment has one task, enforced by a unique resource-type/resource-ID key. Repeating cancellation does not duplicate history or completed tasks. Requests attempt up to 100 due tasks after the transaction commits; outstanding tasks survive process restarts.

Checkout writes a STARTED row in `orders.checkout_attempt` before its first inventory/payment mutation. Order acceptance changes this row to ACCEPTED in the same transaction as the order and its outbox event. Recovery and acceptance lock the same row. A checkout fenced by recovery cannot later accept an order and returns the existing HTTP 409 order-state conflict contract.

Failed checkout still immediately queues known resources, while recovery can discover reservation/payment IDs lost in timed-out responses using privileged order-ID lookup RPCs. STARTED attempts become eligible after ten minutes; failed requests become eligible immediately. Recovery leases attempts for two minutes and rescans them every five minutes, including previously recovered attempts, to discover late remote commits. RECOVERED means a discovery scan succeeded; it does not mean every cleanup task completed.

## Operator retry

An authenticated JWT with the Keycloak realm `admin` role can call:

```http
POST /api/v1/order-cleanup/{orderId}/retry
Authorization: Bearer <operator JWT>
```

Use the order-service origin; deployments must explicitly route this path if exposing it through an ingress. The response contains `completed`, the count acknowledged in this request. A zero result can mean no outstanding work, work not yet due, a lease held elsewhere, an active order that cannot be cleaned up, or failed downstream operations. It is not proof that cleanup is complete.

Tasks are claimed in short database transactions using SKIP LOCKED and a two-minute lease. RPCs run outside database transactions and relay the authenticated caller's token. After failure the task becomes due again after one minute. A crashed request's lease can be reclaimed after two minutes. A lost reply or expired lease can repeat an RPC; release, cancellation and refund intent creation remain idempotent. Cleanup refuses tasks for an existing order whose status is not CANCELLED.

Inspect outstanding work using read-only SQL:

```sql
SELECT id, order_id, resource_type, resource_id, attempts,
       next_attempt_at, lease_until, last_error
FROM orders.cleanup_task
WHERE NOT completed
ORDER BY created_at, id
LIMIT 100;
```

Monitor `order.cleanup.pending`, `order.cleanup.completed`, and `order.cleanup.failures` (Prometheus converts dots to underscores). Stored errors contain exception class names rather than provider payloads or credentials. Payment cleanup validates identity, cancels pending payments and requests an idempotent remaining refund for confirmed/partially refunded payments. Pending or failed refunds stay queued; acknowledgement requires a terminal payment state. See [refund coordination](payment-refunds.md) for webhook completion and operator limits. Completed rows are retained; retention policy remains open.

## Background recovery identity

The worker is disabled by default. Deploy it with a trusted, rotating JWT file and these order-service settings:

```properties
omp.order.recovery.enabled=true
omp.order.recovery.token-file=/run/secrets/order-recovery-jwt
omp.order.recovery.interval-ms=30000
```

The mounted token must be a signed JWT from the issuer trusted by inventory/payment services. Its `scope` claim must include `order.reconcile`, which Spring maps to `SCOPE_order.reconcile`. Deploy the inventory/payment lookup RPCs before enabling order recovery. The repository supplies a development service-account definition, bounded client-credentials projector and explicit Compose overlay; see [identity setup](order-recovery-identity.md). Production identity/secret provisioning remains external. Do not substitute a saved user JWT or a long-lived static token. Mount the projection directory read-only so atomic file replacements remain visible during rotation. Recovery remains disabled in the base topology.

Each tick rereads the file (maximum 16 KiB), opens a thread-local service identity, scans up to ten attempts, and retries up to ten due tasks per order across at most ten orders. The identity is removed on exit. Normal requests continue relaying their caller JWT. The worker stores no bearer token in the journal or task table. Missing/malformed files skip work and increment a failure counter; expired/invalid signatures are rejected downstream. Rotation takes effect on the next tick, and a tick's token must remain valid for its RPC work.

`FindReservationsByOrder` and `FindPaymentsByOrder` are additive unary RPCs restricted to the recovery scope. They return at most 100 resources and fail explicitly above that bound, without creating resources. They return stable INVALID_ARGUMENT, PERMISSION_DENIED, FAILED_PRECONDITION, and INTERNAL errors. The in-process gRPC tests exercise the method scope check; deployed JWT validation and TLS acceptance are separate checks.

Recovery validates each complete lookup result before queueing resources from it.
The result must contain at most 100 distinct, non-null resource IDs, and every
resource must belong to the journal attempt's order. A malformed UUID, duplicate
identity, foreign order or invalid result keeps the scan retryable and increments
`order.recovery.failures`; no prefix of that invalid result is queued. Verified
reservations already queued before a failed payment lookup remain available for
cleanup. Invalid discovery for one attempt does not prevent discovery for the
other claimed attempts or retries of existing cleanup tasks. Resource state does not determine
ownership: cleanup still reads and validates the current remote state before any
mutation or acknowledgment.

Monitor `order.recovery.scans`, `order.recovery.failures`, and `order.recovery.worker.failures` alongside cleanup counters. Inspect attempts with:

```sql
SELECT order_id, status, attempts, recover_after, lease_until, last_error
FROM orders.checkout_attempt
WHERE status <> 'ACCEPTED'
ORDER BY recover_after, order_id
LIMIT 100;
```

## Remaining acceptance

For a reviewed historical orphan UUID with no aggregate or normal checkout
attempt, use the audited [orphan admission procedure](order-orphan-adoption.md).
It creates a recovery intent and preserves normal discovery/cleanup checks.
Existing attempts and orders retain their ordinary lifecycle/retry paths.

Deploy and verify the rotating issuer identity and full checkout failure-injection journeys. The five `CheckoutJournalIT` PostgreSQL cases now pass, covering fencing, rollback, SKIP LOCKED acceptance, expired leases and repeat discovery. Run them from the reactor with `./mvnw -pl order-service -am verify -Dit.test=CheckoutJournalIT -Dfailsafe.failIfNoSpecifiedTests=false`. See [order verification](order-verification.md) for the executed local scope; deployed recovery identity and full-stack acceptance remain open.

Completed tasks and orphan journal rows are retained. Orphan attempts are intentionally rescanned indefinitely; define a retention/archive policy only with a bounded remote-write horizon and recovery evidence. Legacy orphan resources created before the journal migration have no discoverable attempt record and require an initial audit. Confirmed-payment cleanup now uses the provider-backed refund workflow; permanent failures, legacy intents and missing-webhook reconciliation still need operator handling. Inventory TTL expiry remains disabled until the accepted-order lifecycle handles expired holds. The broader gRPC authorization and mTLS review remains open.

Shipment uses a separate durable fulfillment queue. Once an order is FULFILLING,
cancellation is rejected and cleanup continues to exclude it as an active order.
Use the administrator ship action to retry fulfillment, rather than cleanup.
See [fulfillment recovery](order-fulfillment.md) for pending holds, worker identity
and permanent-failure limits.
