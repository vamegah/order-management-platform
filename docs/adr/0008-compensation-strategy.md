# ADR-0008: Compensation Strategy for Cross-Service Consistency

> **Status:** Accepted
> **Date:** 2025-01-15
> **Deciders:** Platform team
> **Supersedes:** —
> **Superseded by:** —

## Context

Order creation involves multiple services:

1. Fetch customer (customer-service).
2. Fetch products (product-service).
3. Reserve stock (inventory-service).
4. Create payment intent (payment-service).
5. Persist order (order-service).

Steps 3 and 4 make **durable state changes in external services**. If step
5 (or an intervening step) fails, steps 3 and 4 have already committed. The
platform needs to handle this.

Traditional ACID transactions don't extend across services and protocols.
Two-phase commit (2PC) is theoretically possible but has known problems:

- **Blocking.** 2PC holds locks across services until all participants vote.
  A slow or partitioned participant blocks everyone.
- **Complexity.** Every participant must implement the 2PC protocol.
- **Heterogeneous protocols.** We mix REST (Feign) and gRPC. A unified 2PC
  would require wrapping every protocol.
- **Not supported by our data stores.** Postgres, inventory-service, and
  payment-service don't expose 2PC interfaces.

## Decision

Use the **saga pattern** with **compensating transactions**.

Every step that commits durable state has a corresponding compensating
operation:

| Step | Compensation |
|------|--------------|
| `inventoryService.reserve(orderId, sku)` | `inventoryService.release(reservationId)` |
| `paymentService.createIntent(orderId, ...)` | `paymentService.cancel(paymentId)` |
| `orderRepository.save(order)` | (no compensation — the local DB transaction either commits or doesn't) |

On failure, order-service runs compensation for all completed steps in
**reverse order**:

1. If payment intent was created → cancel it.
2. If reservations were created → release them (in reverse order).
3. Rethrow the original exception.

Compensations are **best-effort**. If a compensation call fails:
- Log it with the affected resource's ID.
- Continue with the remaining compensations.
- Let Phase 7's background cleanup jobs reconcile orphans.

## Rationale

### Why sagas?

The saga pattern is the industry standard for cross-service consistency
without distributed transactions. It trades strict ACID properties for
**eventual consistency**, which is what practical microservice systems
accept.

- **Non-blocking.** No locks held across services.
- **Simple per step.** Each service exposes its own compensation operation
  (which is just a normal API call).
- **Tolerant of failures.** A failed compensation doesn't deadlock; it's
  logged and reconciled.

### Why compensation in reverse order?

Creating A, then B, then C, then failing means undoing C first, then B,
then A. This mirrors a natural undo stack. Reversing the order avoids
intermediate states that don't make sense (e.g., releasing a reservation
before cancelling the payment intent would leave a window where a
cancelled payment has no reservation to release).

For our current steps, the order doesn't strictly matter (payment and
reservations are independent). But the pattern is future-proof and
consistent with saga literature.

### Why best-effort, not guaranteed?

Guaranteeing a compensation requires either:
- **Persistent saga state** — a saga log that tracks each step and its
  compensation status, with retries.
- **Outbox pattern** — a durable queue of compensation requests that a
  background worker drains.

Both add significant infrastructure. Phase 3's scope is smaller: a
best-effort synchronous compensation with logging. Orphans are cleaned up
by Phase 7's expiration and reconciliation jobs.

**Trade-off acknowledged:** a small number of orphaned reservations or
payment intents may exist between a failed order creation and the
cleanup job. This is acceptable — the resources don't affect any user-facing
functionality and are typically cleaned within hours.

### Why not 2PC?

Covered in Context above. Theoretically cleaner; practically worse for our
constraints.

## Consequences

### Positive

- **No distributed transactions.** No 2PC coordinator, no lock coordination.
- **Simple to reason about.** Each step has an obvious inverse.
- **Resilient to service outages.** Compensation failures don't cascade.
- **Standard pattern.** Well-documented; engineers are familiar with it.

### Negative

- **Eventual consistency.** Between a failed step and the corresponding
  compensation, external services hold state that doesn't correspond to any
  live order. This is a known property of sagas.
- **Compensation logic duplication.** Each step needs both the forward and
  reverse operation, and the failure-handling logic in the orchestrator.
- **Recovery needs an authenticated worker.** Failed compensation remains in a
  durable queue with bounded leased retry. Automatic workers are opt-in; operators
  must configure their identities and verify recovery in deployment.
- **Eventual orphan cleanup.** Reservations and payments can outlive failed
  creation while cleanup/reconciliation is pending.

### Neutral

- **The saga pattern is per-flow.** Order creation is a saga. Order
  cancellation is a saga. Each is implemented independently. A generic
  saga framework (like Temporal or Camunda) would abstract this, but at
  significant infrastructure cost. We use plain Java.

## Implementation

Checkout journals accepted resources and queues cleanup on failure. Cancellation
commits cleanup tasks with local state/outbox changes, then releases holds and
cancels pending payments or requests remaining refunds. Shipment commits durable
fulfillment intent before remote inventory commits and fences cancellation.

See [durable cleanup](../order-cleanup.md), [fulfillment](../order-fulfillment.md)
and [refund coordination](../payment-refunds.md) for idempotency, authenticated
retry and executed persistence/HTTP tests. Complete deployed recovery acceptance
remains open.

## Related Decisions

- **ADR-0005:** gRPC for internal services.
- **ADR-0006:** gRPC client integration.
- [ADR-0012](0012-transactional-outbox.md): transactional outboxes.
- Reservation expiry, checkout cleanup and refund reconciliation have bounded
  opt-in workers; deployed fault/recovery acceptance remains open.

## References

- Saga pattern: https://microservices.io/patterns/data/saga.html
- Compensating transactions:
  https://en.wikipedia.org/wiki/Compensating_transaction
- Temporal (alternative approach): https://temporal.io/
