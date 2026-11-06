# Phase 4 Retrospective — Real-Time Notifications

> Current status: [the acceptance backlog](../../TASKS.md) and
> [implementation evidence](../task-implementation-report.md) supersede
> the original completion claims in this historical record.

> **Status:** Historical implementation record
> **Period:** Phase 4
> **Completed:** 2025-01-15

## What we built

A real-time notification pipeline connecting Kafka events to WebSocket
clients, plus the producers that emit events from state changes.

### Components

| Component | Purpose |
|-----------|---------|
| **shared/events** | Event schemas, Kafka topic definitions, `KafkaEventPublisher` base |
| **notification-service** | Kafka consumers, Redis fan-out, WebSocket server |
| **Producers** | Kafka publishers in order-service, inventory-service, payment-service |
| **Client reference** | TypeScript WebSocket client with reconnection |
| **ADR-0009** | Kafka event architecture |
| **ADR-0010** | WebSocket subscription model |
| **ADR-0011** | Real-time bridge architecture |

### Data flow
State change (order, inventory, payment)
│
▼
Kafka producer (services)
│
▼
Kafka topic
│
▼
notification-service Kafka consumer (one replica)
│
▼
Redis pub/sub (all replicas)
│
▼
Local WebSocket dispatch
│
▼
WebSocket clients


## Metrics

| Metric | Count |
|--------|-------|
| **Batches** | 6 (8.1–8.6) |
| **New services** | 1 (notification-service) |
| **New shared module** | 1 (shared/events) |
| **Kafka topics** | 3 |
| **Event types** | 16 |
| **WebSocket subscriptions supported** | 3 destination patterns |
| **New ADRs** | 3 (0009–0011) |
| **Test files added** | ~20 |
| **Total files added (Phase 4)** | ~110 |
| **Lines of Java** | ~3,200 |
| **Lines of TypeScript (client reference)** | ~150 |

## What worked well

### 1. Event schemas as Java records

JSON with Java records was the right choice for Phase 4:
- Events are readable in Kafka UI without decoding tools.
- No schema registry needed.
- Adding a field is backward-compatible (consumers ignore unknowns).
- The string constants for event types (not enums) enable forward
  compatibility.

The event schema is defined once in `shared/events`. Adding a new event
type is one entry in `EventTypeRegistry` plus one record.

### 2. Redis pub/sub for cross-replica fan-out

The bridge works exactly as designed. Multi-replica fan-out is transparent
to clients — they connect, subscribe, and receive events regardless of
which replica served their WebSocket handshake.

The pattern is well-understood by the industry. No surprises.

### 3. After-commit event publication

Publishing events after the DB transaction commits prevents phantom events.
The `TransactionEventScheduler` wrapper makes this uniform across all
producers.

### 4. Backpressure via disconnect

Setting WebSocket transport limits (256KB buffer, 20s send time) and letting
slow clients disconnect is simple and correct. The client's reference
implementation handles reconnection transparently.

### 5. Comprehensive metrics

The "Pipeline Throughput" panel on the dashboard makes pipeline health
immediately visible:
- Kafka consumed rate.
- Redis published rate.
- Redis received rate.
- WebSocket dispatched rate.

A drop at any stage is obvious.

## What was hard

### 1. The transaction-event timing problem

Publishing events inside a transaction is a common mistake — phantom
events on rollback. Solving this required:
- Understanding `TransactionSynchronizationManager`.
- Wrapping event publication in `afterCommit`.
- Ensuring the wrapper is used uniformly.

**Lesson:** event publication and transactional boundaries are a
persistent source of subtle bugs. Phase 5's outbox pattern will formalize
this.

### 2. JWT propagation to STOMP

The HTTP filter chain doesn't run for STOMP message handling. The JWT from
the CONNECT frame must be stored on the session and retrieved for
downstream REST calls (e.g., ownership verification).

Our solution — `JwtThreadLocalHolder` — works but is a workaround. A
cleaner integration would use Spring Security's `SecurityContextHolder`
populated for STOMP sessions. Phase 7 refinement.

### 3. Client reconnection is tricky

The reference implementation handles:
- Exponential backoff with jitter.
- Re-subscription on reconnect.
- State re-sync via REST.

Every client needs this. The pattern is documented but requires care to
implement correctly.

**Lesson:** a shared TypeScript library would help. Future phases might
publish one.

### 4. Kafka consumer group rebalancing

When a replica restarts, its partitions are reassigned. Consumers
temporarily stop processing during rebalancing. With `session.timeout.ms:
15000` and `heartbeat.interval.ms: 5000`, rebalances are fast but not
instant.

For Phase 4, this is acceptable — brief gaps in event delivery, recovered
by client re-sync. For higher-availability requirements, we'd increase
partition count (more parallel consumers) or use static membership (Kafka
2.3+).

### 5. Testing WebSocket interactions

`WebSocketStompClient` is functional but verbose. Each test:
- Starts a full Spring context.
- Opens a WebSocket connection.
- Sends STOMP frames.
- Waits for responses.

**Slower than unit tests, but essential.** We have both layers.

## What we'd do differently

### 1. Extract the `TransactionEventScheduler` earlier

We initially defined it in order-service, then moved it to shared/events
when inventory-service needed it too. Should have been in shared from
the start.

**Lesson:** the second use of a utility is the right time to extract it.

### 2. Consider the transactional outbox from the start

Best-effort publishing works, but "the DB committed but the event was
lost" is a real gap. The transactional outbox pattern (write events to a
DB table inside the same transaction; a background job drains it) is the
correct solution. Deferring it to Phase 5 means a window where events can
be lost.

**For Phase 4's operational profile** (single-host dev, low volume),
the window is small. For a production deployment, this would be a
priority.

### 3. Simplify the JWT handling

The `JwtThreadLocalHolder` workaround is a code smell. A cleaner solution
would be to configure Spring Security to populate the
`SecurityContextHolder` for STOMP sessions properly (the deprecated
`AbstractSecurityWebSocketMessageBrokerConfigurer` was doing this, but
we're using it minimally).

**Phase 7 refinement.**

### 4. Aggregate per-user subscriptions sooner

The deferred `/topic/users/{userId}/notifications` topic would be useful
for many clients. The blocker was the JWT `customer_id` claim gap.

**Fix:** update the Keycloak realm to include `customer_id` in JWTs.
This is a Phase 5 task.

## Deferred to later phases

| Item | Target Phase |
|------|--------------|
| Transactional outbox | Phase 5 |
| User-scoped notifications topic | Phase 5 |
| Shared TypeScript WebSocket client | Phase 5 (if mobile apps appear) |
| Explicit per-subscription unsubscribe tracking | Phase 7 |
| Cleaner STOMP security integration | Phase 7 |
| Redis Sentinel for fan-out HA | Already configured in prod overlay |
| Client-side message buffering | Phase 7 |
| Snapshot on subscribe | Deferred (client fetches via REST) |

## Conclusion

Phase 4 delivered:

1. **A durable event bus** (Kafka) with 16 event types across 3 topics.
2. **A real-time push service** (notification-service) that consumes
   events and pushes to WebSocket clients.
3. **Cross-replica fan-out** via Redis pub/sub.
4. **Full authentication and authorization** for WebSocket sessions.
5. **A reference client** with reconnection and backpressure handling.
6. **Comprehensive observability** — metrics at every pipeline stage.

The **primary learning** is that bridging synchronous (Kafka) and
asynchronous (WebSocket) systems requires care. The Redis pub/sub layer
solves the multi-replica problem elegantly. The pattern is well-established
and the implementation is straightforward once understood.

The **primary risk** is event loss. Best-effort publishing and ephemeral
Redis pub/sub mean events can be lost during service restarts or brief
outages. Clients mitigate by re-syncing state on reconnect. Phase 5's
outbox pattern will close the durability gap.

**Phase 4 is complete.**