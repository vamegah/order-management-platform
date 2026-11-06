
---

## File: `docs/adr/0009-kafka-event-architecture.md`

```markdown
# ADR-0009: Kafka Event Architecture

> **Status:** Accepted
> **Date:** 2025-01-15
> **Deciders:** Platform team
> **Supersedes:** —
> **Superseded by:** —

## Context

Through Phase 3, all inter-service communication has been **synchronous**:
- REST (Feign) for order-service → product-service, customer-service.
- gRPC for order-service → inventory-service, payment-service.

Phase 4 introduces **WebSocket** for real-time client notifications. This
requires the notification-service to know about state changes in orders,
inventory, and payments as they happen.

Two architectural options:

**A. Polling.** notification-service polls the other services for state
changes (e.g., every second, ask order-service "any new orders?").

**B. Event streaming.** The owning services publish events to a message
bus when state changes. notification-service consumes events and forwards
them to WebSocket clients.

Polling has obvious problems:
- **Latency.** Notifications arrive up to one poll interval late.
- **Load.** Every service gets hammered with queries even when nothing
  changed.
- **Coupling.** notification-service must know each service's query API.
- **Missed events.** A state change between two polls is either lost or
  re-detected (idempotency overhead).

Event streaming is the standard solution. The platform already has a Kafka
cluster running in the dev and prod environments (set up in Phase 0),
currently unused by any service.

## Decision

Adopt **Kafka as the platform's event bus.** Services that own state
publish events when the state changes. Consumers subscribe to the topics
they care about.

**Scope (Phase 4):**
- **Order events** (lifecycle transitions) → topic `orders.events.order`.
- **Inventory events** (reservations) → topic `inventory.events.reservation`.
- **Payment events** (state changes) → topic `payments.events.payment`.

**Event format:** JSON, defined as Java records in `shared/events`.

**Partitioning:** all events for one aggregate are keyed by the aggregate's
ID, preserving per-aggregate ordering.

**Retention:** 7 days in dev, 30 days in prod.

## Rationale

### Why Kafka (and not RabbitMQ, Redis pub/sub, or in-process events)?

- **Durability.** Kafka persists events to disk. A consumer that falls
  behind can catch up. Redis pub/sub is ephemeral; a subscriber that
  misses a message never sees it.
- **Replay.** Kafka's retention window allows replaying events (e.g., to
  rebuild a consumer's state after a bug fix). This is not possible with
  Redis pub/sub.
- **Ordering per partition.** Kafka guarantees ordering within a partition.
  Keying by aggregate ID gives us per-aggregate ordering for free.
- **Consumer groups.** Multiple consumers can subscribe independently,
  each getting all events, without interference.
- **Existing infrastructure.** The Kafka cluster is already running (Phase 0).
- **Industry standard.** Every backend engineer knows Kafka.

RabbitMQ is comparable for our needs but would require a new deployment.
Redis pub/sub is already running but doesn't persist. In-process events
(Spring `ApplicationEventPublisher`) don't cross service boundaries.

### Why JSON, not Avro?

- **No schema registry needed.** Avro requires a Schema Registry (Confluent
  or an equivalent) for schema evolution. That's a new piece of infrastructure.
- **Human-readable.** JSON events can be inspected with `kafka-console-consumer`
  without any decoding tools. This matters a lot for debugging and demos.
- **Consistent with the platform.** REST is JSON. gRPC is Protobuf. Adding
  Avro would be a third serialization format; JSON is one we already use.
- **Sufficient for our scale.** Events are 5–15 fields. JSON overhead
  (~2–3x vs Avro) is negligible for a few hundred events per second.

**For future scale:** if event volume reaches tens of thousands per second,
we migrate to Avro. The Java records stay the same; only serialization changes.

### Why string constants for event types?

Java enum would give compile-time exhaustiveness. But:
- **Forward compatibility.** A new event type would fail to deserialize in
  old consumers if it's an enum. With strings, unknown types pass through
  and consumers ignore them.
- **Cross-service consistency.** String constants shared across services
  work regardless of Java version or classpath.
- **Cross-language.** If a non-Java service ever produces events, strings
  are universal.

**Trade-off:** no exhaustiveness check. Mitigated by centralizing constants
in `OrderEventType`, `InventoryEventType`, `PaymentEventType`.

### Why per-aggregate partitioning (key = aggregate ID)?

Kafka guarantees ordering within a partition. Across partitions, no
ordering. If we don't key events, they distribute round-robin, and a
consumer could process "order shipped" before "order placed."

Keying by `orderId` ensures all events for one order land in the same
partition and are consumed in order. This is essential for notification
correctness (a client should see status transitions in order).

**Cross-aggregate ordering is NOT guaranteed.** An order-created event and
an inventory-reservation event for that order might be processed in any
order. Consumers that need cross-aggregate ordering must handle this
explicitly.

### Why programmatic topic provisioning?

Kafka can create topics automatically on first publish (`auto.create.topics.enable`).
That's convenient but wrong:
- Broker-default partitions, replication, retention — not ours.
- No way to enforce our topic naming convention.
- Silent topic explosion if a typo produces events.

Programmatic provisioning via the Admin API:
- Runs at service startup, before any producer publishes.
- Uses our explicit topic specifications.
- Idempotent (skips existing topics).
- Version-controlled (the specification is code, not a shell script).

## Consequences

### Positive

- **Decoupling.** Producers don't know who consumes their events. Adding a
  new consumer requires zero producer changes.
- **Durability.** Events survive consumer downtime up to the retention window.
- **Replay.** Events can be re-consumed to rebuild state.
- **Per-aggregate ordering.** Guaranteed by keying on aggregate ID.
- **Observability.** Kafka UI shows topics, offsets, consumer lag. Powerful
  for debugging.
- **Backpressure handling.** Consumers can lag without affecting producers.
- **Standard pattern.** Event-driven microservices are the industry norm.

### Negative

- **Eventual consistency.** The notification service receives events with
  some delay (typically < 100ms). It doesn't reflect the state of the
  platform at the exact moment a client connects. Handled by sending an
  initial snapshot from a REST call, then following events.
- **Two-phase state.** A service changes its DB and publishes an event.
  These aren't atomic. If the DB commits but the event publish fails, the
  event is lost. **Phase 5** will introduce the **transactional outbox**
  pattern to make this consistent.
- **Schema evolution discipline.** Adding fields is safe; removing them is
  not. Consumers must ignore unknown fields (enforced via
  `@JsonIgnoreProperties(ignoreUnknown = true)` in the ObjectMapper).
- **More infrastructure to monitor.** Kafka lag, broker health, topic
  metrics. Mitigated by the existing Prometheus + Grafana stack.
- **Consumer group management.** Rebalancing during deploys can cause brief
  processing delays. Standard Kafka operational concern.

### Neutral

- **Additional serialization format.** We already had JSON (REST) and
  Protobuf (gRPC); Kafka uses JSON. The platform now has "one format per
  protocol" instead of "one format for everything."

## Implementation

- **Event schemas:** `shared/events`.
- **Topic specifications:** `TopicsConfig`.
- **Provisioning:** `TopicProvisioner` (runs at service startup).
- **Producers:** added per service in Batches 8.2–8.4.
- **Consumers:** notification-service in Batch 8.4.

## Related Decisions

- **ADR-0001:** Schema-per-service.
- **ADR-0005:** gRPC for internal services.
- **ADR-0006:** gRPC client integration.
- [ADR-0012](0012-transactional-outbox.md): transactional outbox.

## References

- Kafka documentation: https://kafka.apache.org/documentation/
- Transactional outbox pattern:
  https://microservices.io/patterns/data/transactional-outbox.html
- Consumer group semantics:
  https://kafka.apache.org/documentation/#consumerconfigs_group.id