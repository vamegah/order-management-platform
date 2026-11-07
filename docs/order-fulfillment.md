# Durable order fulfillment

> **Status:** Implemented locally; PostgreSQL and deployed recovery acceptance pending
> **Last reviewed:** 2026-10-29

Shipment now follows `CONFIRMED → FULFILLING → SHIPPED`. A matching, fully confirmed
payment is required to request shipment. The first local transaction records the
FULFILLING history entry, one fulfillment request and one task per order line.
Each line must have its own reservation reference. Existing legacy orders without
those references cannot begin shipment until their data has been reconciled.
The [administrator repair procedure](order-reservation-repair.md) can restore
missing references to verified existing holds before fulfillment begins.

The intent commits before inventory RPCs. Optimistic order versions resolve a
concurrent cancellation/request race; after FULFILLING commits, cancellation is
rejected. This prevents cancellation from releasing some holds after other holds
have already been consumed. FULFILLING is a pending, nonterminal state and is
included in REST filters, order history and the GraphQL OrderStatus enum.

## API behavior

The existing administrator action starts or retries fulfillment:

```http
POST /api/v1/orders/{orderId}/ship
Authorization: Bearer <operator JWT>
Content-Type: application/json

{"reason":"Ready for dispatch"}
```

The request attempts up to 100 due tasks. Success returns HTTP 200 with SHIPPED.
Incomplete work returns HTTP 202 with FULFILLING, `Location` pointing to the order
read endpoint, and `Retry-After: 60`. Poll that endpoint or repeat the administrator
action. Repeating shipment after SHIPPED/DELIVERED returns the existing order
without another intent, history entry or shipment event. GraphQL `shipOrder`
accepts the downstream 202 and returns the FULFILLING enum value.

FULFILLING introduces a new status and 202 response. Deploy the migration, order
service and updated GraphQL schema together, and update clients that assume a
closed status enum or an immediate 200/SHIPPED response. Existing historical
shipments are preserved; their history need not contain FULFILLING.

## Execution and recovery

The executor rechecks payment before consuming more stock on each attempt. It
reads each reservation and verifies reservation ID, order ID, SKU and quantity
before committing it. An ACTIVE hold is committed with a bounded gRPC call; an
already COMMITTED hold can be acknowledged without another mutation. Responses
are checked again before the task is marked complete. EXPIRED/RELEASED/unknown
states and mismatched responses remain pending and cannot produce SHIPPED.

Task claims use a short PostgreSQL transaction, SKIP LOCKED and a two-minute lease.
Only one task is leased at a time per executor, so later tasks do not spend their
lease waiting behind earlier RPCs. Each claim has a new lease ID; an old worker
cannot acknowledge a replacement lease. Failures retry after one minute. Due
requests move back in the polling order when attempted, so blocked orders do not
permanently monopolize the first batch. No database transaction spans an RPC;
the executor rejects an ambient transaction and preserves interruption.

A lost commit reply is recovered by rereading the hold. A crash after task
acknowledgement but before finalization resumes completion without consuming
stock again. The finalizer serializes on the request row, requires every task to
be complete, rechecks a freshly read payment against the current order, and commits
SHIPPED, its history, the existing shipment outbox event and request completion
together. The internal FULFILLING step is represented by durable work/history;
it does not introduce a new Kafka event type.

PostgreSQL's [locking-clause documentation](https://www.postgresql.org/docs/16/sql-select.html#SQL-FOR-UPDATE-SHARE)
describes the queue-oriented SKIP LOCKED behavior used here. Database concurrency
and restart behavior still need executed PostgreSQL acceptance.

## Background worker and operator visibility

Immediate execution uses the requesting administrator's JWT. The optional worker
is disabled by default. Enable it only with a trusted, rotating workload JWT file:

```properties
omp.order.fulfillment.enabled=true
omp.order.fulfillment.token-file=/run/secrets/order-fulfillment-jwt
omp.order.fulfillment.interval-ms=30000
spring.task.scheduling.pool.size=3
```

Provision an identity accepted by the inventory/payment services for reservation
read/commit and payment read. This repository does not provision or refresh it.
Mount the file read-only and replace it atomically during rotation. Each tick
rereads at most 16 KiB, restores the previous thread identity on exit, and processes
at most ten due orders with ten tasks per order. Missing/malformed/oversized tokens
skip work. Tokens are never persisted in fulfillment rows or logs. Downstream
services remain responsible for signature, issuer, expiry and authorization checks.
Use enough scheduler threads for the outbox, checkout recovery and fulfillment
jobs to run independently; the example allocates three. Spring's single-thread
default can delay outbox draining while a worker waits on downstream RPCs.

Monitor `order.fulfillment.pending`, `order.fulfillment.failures`,
`order.fulfillment.reservations.completed`, `order.fulfillment.completed`, and
`order.fulfillment.worker.failures`. Stored errors contain exception class names,
not downstream response bodies or credentials. Inspect pending requests/tasks:

```sql
SELECT request.order_id, request.attempts, request.next_attempt_at,
       request.last_error, task.reservation_id, task.completed,
       task.lease_until, task.last_error AS task_error
FROM orders.fulfillment_request request
JOIN orders.fulfillment_task task USING (order_id)
WHERE request.completed_at IS NULL
ORDER BY request.next_attempt_at, request.order_id, task.id
LIMIT 100;
```

An expired hold, a payment refunded during fulfillment, or a persistent identity
mismatch requires investigation. The order remains FULFILLING; automatic release,
restock, refund and cancellation are deliberately absent once commits may have
occurred. Existing refund APIs and shipment are not one cross-service transaction;
payment can still change after its final read. Fulfillment records are retained;
retention policy and recovery from permanent failures remain open. Inventory TTL
expiry stays disabled until the broader accepted-order/refund policy is complete.

## Verification

Unit/in-process tests cover HTTP 200/202 contracts, GraphQL 202 decoding, caller
token/deadline propagation, hold mismatches, expired holds, partial progress,
lost replies, interrupted workers, payment changes and credential rotation.
`OrderPersistenceIT` adds PostgreSQL tests for intent rollback, expired leases,
stale acknowledgements, single finalization, outbox rollback and transaction-free
RPC execution. Docker is required for those database tests.

```sh
./mvnw -pl order-service,graphql-gateway -am test
./mvnw -pl order-service -am verify -Dit.test=OrderPersistenceIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

See [the implementation report](task-implementation-report.md) for executed
results. These checks do not establish physical carrier dispatch, deployed
broker delivery, mTLS or complete lifecycle acceptance.
