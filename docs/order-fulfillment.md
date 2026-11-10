# Durable order fulfillment

Shipment follows `CONFIRMED → FULFILLING → SHIPPED`. Cancellation after work begins
uses `CANCELLING` until the durable payment authority decides between shipment and
compensation. See the [complete lifecycle matrix](core-order-lifecycle.md).

A matching, fully confirmed payment and verified reservation references are
required to request shipment. The first transaction records FULFILLING history,
one fulfillment request and one task per line, including known warehouse
provenance. It commits before inventory RPCs. Optimistic order versions resolve
concurrent local commands; all public lines remain immutable.

## API behavior

`POST /api/v1/orders/{id}/ship` remains an administrator action. It starts or
retries at most 100 due tasks. Completed work returns 200/SHIPPED; pending work
returns 202/FULFILLING with `Location` and `Retry-After: 60`. A competing cancellation
can return 202/CANCELLING. Poll the order or repeat the authorized action. Repeating
shipment after SHIPPED/DELIVERED returns the original aggregate without a new
intent, history entry or shipment event. GraphQL preserves the same pending states.
Deploy the order migration and GraphQL enum together and update closed-enum clients.

## Decisions, execution and restart recovery

The executor checks the complete payment/order identity and amount before asking
payment-service for BEGIN. Refund admission and fulfillment decisions share the
same locked payment row. A pending refund prevents BEGIN, and BEGIN blocks new
refunds. These decisions have no lease timeout; a crashed worker cannot implicitly
authorize a refund.

Each inventory reply must match reservation ID, order, SKU, quantity and any known
warehouse. An ACTIVE hold is committed with a bounded RPC; an already COMMITTED
hold can be acknowledged without another mutation. An expired/released/restocked
required hold requests cancellation and stops additional commits. A transient
transport failure retains due work and allows other owned tasks to progress.

Task claims use PostgreSQL SKIP LOCKED and a two-minute lease. An expired or replaced
lease cannot acknowledge a task. Failures retry after one minute. No transaction
spans an RPC. A lost commit reply is recovered by rereading the hold; durable task
acknowledgments survive process restart without consuming stock again.

Only after every task is acknowledged does the executor request the durable SHIP
decision. Finalization serializes on the fulfillment request, validates the payment
against the current order and commits SHIPPED, history, shipment outbox event and
request completion together. Cancellation that already won prevents SHIP. If SHIP
won before local finalization, reconciliation completes SHIPPED and does not undo
stock or admit refunds.

If cancellation wins, the coordinator compensates all owned inventory atomically,
including restocking partial commits once. Inventory retains a tombstone against
late writes. Payment moves to REFUNDABLE only after this acknowledgment. Cleanup
then cancels pending payments or refunds remaining paid amounts. Failed or unknown
outcomes remain durable, leased and observable; an operator retry changes scheduling
and records a verified actor/reason without bypassing the decisions.

## Supervised worker

The [private workload identity procedure](order-recovery-identity.md) provisions
the scoped token projector. Combine the lifecycle/recovery and supervised projector
Compose overlays for automatic accepted-order and orphan recovery. The lifecycle
worker retries both accepted-order coordination and fulfillment. It rereads the
projection each tick and restores the prior thread identity on success or failure.
The cancellation fast path uses the same configured workload identity. Credentials
are never stored in work rows or logs.

The separate fulfillment-only worker remains available through
`omp.order.fulfillment.enabled` and `omp.order.fulfillment.token-file`; use a trusted,
rotating identity with `order.reconcile` scope. For complete compensation and expiry
coordination, use the lifecycle worker. See [deployment, metrics, bounds and operator
routes](core-order-lifecycle.md#deployment-and-operations).

PostgreSQL and deployed acceptance cover duplicate requests, task acknowledgment
loss, lease replacement, outbox rollback, partial commits, container restart,
natural reservation expiry and concurrent refund/shipment decisions. The exact
executed evidence belongs in the [implementation report](task-implementation-report.md).
