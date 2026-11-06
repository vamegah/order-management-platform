# ADR-0011: Real-Time Architecture — Bridging Kafka and WebSocket

> **Status:** Accepted
> **Date:** 2025-01-15
> **Deciders:** Platform team
> **Supersedes:** —
> **Superseded by:** —

## Context

Phase 4 adds real-time notifications. Two decisions are already documented:

- **ADR-0009:** Kafka as the internal event bus.
- **ADR-0010:** STOMP over WebSocket with per-resource subscriptions.

This ADR addresses the bridge between them: **how does an event published
on Kafka reach a WebSocket client, potentially connected to a different
service replica?**

Kafka's consumer group semantics deliver each event to exactly one consumer.
In a multi-replica notification-service deployment, that means:

- Replica A consumes event E.
- Replica B never sees E through Kafka.
- A client connected to replica B misses the event.

This is the fundamental problem the bridge must solve.

## Decision

Insert **Redis pub/sub** between Kafka consumers and WebSocket dispatch:
Kafka event
│
│ consumed by exactly one notification-service replica
▼
Redis pub/sub (broadcast to ALL replicas)
│
│ each replica receives the event
▼
Local WebSocket dispatch to that replica's subscribers


**Concretely:**

1. Kafka consumers forward every event to a Redis channel (`notifications:orders`, etc.).
2. Every notification-service replica subscribes to all channels.
3. On receiving an event via Redis, each replica dispatches to its locally-connected WebSocket subscribers.

**Kafka offset commit happens after the Redis publish**, not after WebSocket delivery.

## Rationale

### Why not a unique Kafka consumer group per replica?

Alternative: each replica has a unique group ID (e.g.,
`notification-service-<uuid>`). Then all replicas consume all events
independently, and no Redis bridge is needed.

**Rejected because:**
- **Offset management chaos.** Each replica has its own offsets. Lag
  monitoring becomes N-times more complex.
- **Replay problems.** When a replica restarts, its group has no committed
  offsets (new group ID) and reads from `earliest` or `latest` based on
  config — inconsistent behavior across replicas.
- **Resource waste.** Every event is deserialized N times.
- **No natural "one event, one processing" semantics.** Debugging is
  harder.

### Why not sticky sessions?

Alternative: ensure a client always connects to the same replica (sticky
routing). Then the replica that consumes the event is the replica that
hosts the client.

**Rejected because:**
- Traefik would need sticky sessions (consistent hashing on client IP or
  a cookie).
- Sticky sessions break when a replica restarts — the client is
  reconnected to a different replica.
- Doesn't handle the case where the client reconnects mid-event.
- Adds load-balancer complexity that's easy to get wrong.

### Why not Kafka's partition assignment as a routing mechanism?

Alternative: use a custom partitioner that assigns the partition based on
the client's session ID, so a client's events always go to the replica
hosting them.

**Rejected because:**
- Replicas would need to rebalance partitions on every client connect
  and disconnect — chaos for consumer group membership.
- Kafka partitions are limited (3 in dev, 6 in prod). Each partition can
  serve thousands of clients; the mapping is not 1:1.
- Requires session-to-partition registry (a stateful service, defeating
  the stateless design).

### Why Redis pub/sub?

- **Broadcast semantics.** Every subscriber gets every message. Exactly
  what we need.
- **Already in the stack.** Redis runs in dev and prod since Phase 0.
- **Sub-millisecond latency.** Local to the host.
- **Simple to reason about.** One publish, N subscribers, all get the
  message.
- **No additional infrastructure.** No Zookeeper, no Consul, no etcd.

**Trade-offs accepted:**
- **Ephemeral.** Redis pub/sub doesn't persist messages. A replica that's
  down at publish time misses the event. Clients reconnect and re-sync
  via REST.
- **No delivery guarantee.** Redis pub/sub is fire-and-forget. A
  subscriber that's slow might not receive the message.
- **Redis as an extra hop.** ~1ms latency added.

### Why commit Kafka offset after Redis publish, not after WebSocket delivery?

- **Redis publish is synchronous and fast.** Once it returns, the event
  is delivered to all subscribers.
- **WebSocket delivery is asynchronous.** Dispatch happens on a different
  thread; acknowledging individual client receipt would require per-client
  acknowledgments, which STOMP doesn't provide for topic subscriptions.
- **Duplicate risk is acceptable.** If the consumer crashes after Redis
  publish but before Kafka commit, on restart it re-publishes. Subscribers
  see the event twice. Clients are idempotent (they apply state changes
  that are already in their state).

**Consequence:** at-least-once delivery from Kafka through Redis to
WebSocket. Duplicates are possible but harmless.

## Consequences

### Positive

- **No client misses events, regardless of replica.** The bridge guarantees
  every subscriber on every replica receives every event.
- **Kafka semantics preserved.** One consumer group, one offset stream,
  simple lag monitoring.
- **Stateless notification-service replicas.** Any replica can serve any
  client. Horizontal scaling is trivial.
- **No sticky sessions required.** Standard round-robin load balancing
  works.

### Negative

- **Redis as a single point of failure.** If Redis goes down, fan-out
  stops. Mitigated by Redis Sentinel in the prod overlay (Phase 0).
- **Event delivery not guaranteed.** A replica down at publish time misses
  the event.
- **Extra hop and infrastructure.** ~1ms latency and one more service to
  monitor.
- **Debugging requires understanding three systems.** Kafka, Redis, and
  WebSocket each have their own tooling. Trace propagation across all
  three is essential (and implemented).

### Neutral

- **Redis channels are per-domain** (`notifications:orders`,
  `notifications:inventory`, `notifications:payments`). Not per-resource.
  Every replica receives every event; filtering happens locally via the
  SubscriptionRegistry.
- **Duplicate events are tolerated.** No deduplication layer. Clients
  handle idempotency via their state model.

## Implementation

- **Kafka consumers:** forward to Redis after consumption.
- **Redis publisher:** one channel per event domain.
- **Redis subscriber:** deserializes and dispatches via `EventDispatcher`.
- **EventDispatcher:** looks up subscriptions and sends via
  `SimpMessagingTemplate`.
- **Metrics:** `kafka.events.consumed`, `redis.events.published`,
  `redis.events.received`, `websocket.events.dispatched`.

## Related Decisions

- **ADR-0009:** Kafka event architecture.
- **ADR-0010:** WebSocket subscription model.
- **Phase 7 planned:** replace Redis pub/sub with a durable broadcast
  mechanism if event loss becomes unacceptable.

## References

- Redis pub/sub: https://redis.io/docs/interact/pubsub/
- Kafka consumer groups: https://kafka.apache.org/documentation/#consumerconfigs_group.id