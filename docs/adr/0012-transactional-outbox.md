# ADR-0012: Transactional Outbox

> **Status:** Accepted
> **Date:** 2025-01-15
> **Deciders:** Platform team
> **Supersedes:** —
> **Superseded by:** —

## Context

Phase 4 introduced Kafka events for cross-service communication. Services
publish events after state changes, and downstream consumers
(notification-service) react to them.

The publication path has a durability gap:

1. A service commits a state change to its database.
2. The service then calls `KafkaTemplate.send(...)`.
3. If the process crashes between step 1 and step 2, the state change is
   durable but the event is lost forever.

This is the "dual write" problem: two systems (Postgres and Kafka) must be
updated consistently, but no transaction spans them.

Consequences of the gap:

- A customer's order is placed, but no `ORDER_PLACED` event fires.
  Notification-service never pushes the update. The customer's UI is stale.
- An inventory reservation is created, but no `RESERVATION_CREATED` event
  fires. A downstream stock dashboard doesn't update.
- A payment is confirmed, but no `PAYMENT_CONFIRMED` event fires. The
  customer never receives a receipt notification.

The window is small (milliseconds) but real. In a system that runs for
months, it will eventually happen. With at-least-once expectations for
business events, "eventually lost" is not acceptable.

## Decision

Adopt the **transactional outbox pattern**.

Each service that produces events has an `outbox_event` table in its own
schema. The pattern is:

1. **Write phase.** The service writes the state change AND an
   `outbox_event` row in the same database transaction. The state change and
   the event are committed together or not at all.

2. **Drain phase.** A background process (`OutboxDrainer`) polls the outbox
   table for PENDING rows, publishes each to Kafka, and marks it PUBLISHED.

3. **Retry phase.** Failed publish attempts increment a per-row counter.
   After N attempts (default 10), the row transitions to FAILED and requires
   operator intervention.

This gives **at-least-once delivery**: an event is guaranteed to reach Kafka
if the database commit succeeded, and consumers must be idempotent (they
already are).

## Rationale

### Why not a distributed transaction (2PC / XA)?

Kafka and Postgres both support XA transactions, but combining them is:

- **Fragile.** XA coordinators are famously difficult to operate. Recovery
  from a coordinator failure during a two-phase commit leaves locks in
  unknown states.
- **Slow.** Both systems hold locks across the entire transaction.
- **Not necessary.** The outbox pattern provides the same guarantee
  (at-least-once delivery) with simpler mechanics.

### Why not just retry the Kafka send more aggressively?

Retries don't close the gap. If the process crashes between commit and the
first send, no amount of retry logic helps — the code is no longer running.

### Why not Debezium / Kafka Connect?

Debezium reads the Postgres WAL and streams changes to Kafka. It works
without any application-level outbox, because the WAL itself is the source
of truth.

But:

- **Adds infrastructure.** A Kafka Connect cluster, Debezium connectors, and
  their operational surface.
- **Every service's events flow through a shared pipeline.** A single
  Debezium configuration controls all events across all services. Changes
  require coordinating Debezium.
- **The outbox is a smaller, self-contained pattern.** It lives in the
  service that owns the events, uses existing infrastructure (the service's
  own database, Kafka), and can be understood by any engineer familiar with
  Postgres.

For Phase 5's scale, Debezium is overkill. A Phase 7 migration to Debezium
is possible if event volume grows enough to justify it.

### Why per-service outboxes rather than a shared outbox table?

The transactional guarantee requires the outbox row and the state change to
be in the same database. A shared outbox schema would require cross-schema
writes — breaking the schema-per-service boundary established in ADR-0001.

A per-service outbox keeps each service self-contained:

- `orders.outbox_event` belongs to order-service.
- `inventory.outbox_event` belongs to inventory-service.
- `payments.outbox_event` belongs to payment-service.

Each service's drainer queries only its own table. If a service is
horizontally scaled, its drainers scale with it.

### Why `SELECT ... FOR UPDATE SKIP LOCKED` for claiming rows?

Standard Postgres pattern for concurrent queue workers. Two drainers
running in parallel claim disjoint batches — no double-publish across
drainers within a single transaction window.

Without `SKIP LOCKED`, the second drainer would block waiting for the
first drainer's locks to release. That's correct but slower.

### Why synchronous Kafka send in the drainer?

The drainer calls `kafkaTemplate.send(...).get()` — blocking until Kafka
acknowledges. This makes the outcome observable within the drain cycle, so
we know whether to mark the row PUBLISHED.

Async send would require a callback that commits the row individually —
more complex transaction handling, more failure modes. The synchronous
version is simpler and adequate for the batch sizes involved.

## Consequences

### Positive

- **No event loss.** If the state change commits, the event will
  eventually be published to Kafka, even across crashes.
- **Simple.** ~200 lines of code, all in the shared/events module.
- **Portable.** Works with any JPA-capable database, not just Postgres.
- **Observable.** The outbox table shows pending/failed rows at any moment;
  Grafana panels surface backlog and error rates.
- **Per-service ownership.** No cross-schema coupling; no shared external
  infrastructure.

### Negative

- **Duplicates possible.** If the drainer crashes after Kafka publish but
  before DB commit, the event is re-published. Consumers must be idempotent
  (they are).
- **Latency.** Events reach Kafka after the next drain cycle, not
  immediately after commit. Default poll interval is 1 second. For Phase 5,
  acceptable. If lower latency is required, reduce the interval.
- **Extra database writes.** Every event-producing transaction writes one
  more row. Modest overhead; batched by the same transaction.
- **Storage growth.** Published rows are retained for 7 days by default.
  Manageable; can be tuned.

### Neutral

- **One more table per service.** Four services × one table each = four
  tables. Trivial.
- **One more background thread.** The drainer runs on Spring's default
  task scheduler. Non-negotiable cost for the pattern.

## Implementation

- `shared/events/src/main/java/com/example/omp/shared/events/outbox/`
  contains all the outbox classes.
- Each event-producing service (`order-service`, `inventory-service`,
  `payment-service`) adds a Flyway migration that creates
  `outbox_event` in its own schema.
- The outbox auto-configuration activates only when both Kafka and JPA are
  on the classpath.
- Producers call `OutboxWriter.write(event, topic)` inside the same
  transaction that changes the state.

## Related Decisions

- **ADR-0009:** Kafka event architecture.
- **ADR-0011:** Real-time architecture — Kafka → Redis → WebSocket.
- **Phase 7 planned:** Consider Debezium if event volume justifies it.

## References

- Microservices.io — Transactional Outbox pattern:
  https://microservices.io/patterns/data/transactional-outbox.html
- Postgres — `SELECT ... FOR UPDATE SKIP LOCKED`:
  https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE