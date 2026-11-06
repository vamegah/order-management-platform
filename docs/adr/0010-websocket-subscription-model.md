
---

## File 22: `docs/adr/0010-websocket-subscription-model.md`

```markdown
# ADR-0010: WebSocket Subscription Model

> **Status:** Accepted
> **Date:** 2025-01-15
> **Deciders:** Platform team
> **Supersedes:** —
> **Superseded by:** —

## Context

Phase 4 adds real-time notifications via WebSocket. Clients need to
subscribe to updates for specific resources (orders, payments) or categories
(inventory SKUs).

Two design questions:

1. **What protocol on top of WebSocket?** Raw frames, or a messaging
   protocol like STOMP?
2. **What subscription model?** Per-resource subscriptions, or a single
   global stream with client-side filtering?

## Decision

Use **STOMP over WebSocket** with **per-resource subscriptions**.

### STOMP, not raw WebSocket

- STOMP provides subscription semantics (SUBSCRIBE, UNSUBSCRIBE frames),
  message framing, and a small set of standard frames.
- Spring's `@EnableWebSocketMessageBroker` implements STOMP natively.
- Client libraries (`@stomp/stompjs`, `sockjs-client`) are mature and
  widely used.
- Raw WebSocket would require us to define our own message protocol —
  reinventing STOMP.

### Per-resource subscriptions

Destinations are structured like REST resource paths:
- `/topic/orders/{orderId}` — updates for one order.
- `/topic/payments/{paymentId}` — updates for one payment.
- `/topic/inventory/{sku}` — stock updates for one SKU.

Alternative considered: a single `/topic/events` stream where clients
receive all events and filter locally. Rejected because:
- Sends unnecessary data to clients.
- Enables clients to see events for resources they don't own (breaks
  authorization boundaries).

### In-memory subscription registry

Subscriptions are held in-memory in a `SubscriptionRegistry`, tied to
the WebSocket session's lifetime. No persistence.

Alternative considered: persist subscriptions in Postgres so they survive
restarts. Rejected because:
- Subscriptions are meaningless without a live WebSocket connection. A
  persisted subscription can't deliver messages to a disconnected client.
- Adds a database dependency to a service that otherwise has none.

**Consequence:** on notification-service restart (or on client
disconnect), subscriptions are lost. Clients re-subscribe on reconnect.
Documented.

### Ownership verification at SUBSCRIBE time

For resource-scoped subscriptions (orders, payments), the service verifies
that the client owns the resource:
1. Resolves the JWT's `sub` to a customer ID via customer-service `/me`.
2. Fetches the resource via the owning service's API.
3. Compares.

**Cost:** two REST calls per subscription. Acceptable — subscriptions
are infrequent.

**Alternative considered:** filter at push time. Rejected because:
- It requires per-event filtering (more work at higher frequency).
- It defers authorization failure to the push (silent no-op) instead of
  rejecting the subscription (immediate error).

## Rationale

### Why not authenticated WebSocket handshake?

Browsers can't set custom headers on the WebSocket upgrade request. Options
considered:

- **Query string token** — `wss://host/ws?token=...`. Leaks tokens into
  access logs and browser history.
- **Cookie-based** — requires session state and REST login. Couples the
  service to a session model.
- **STOMP CONNECT header** — token travels in a STOMP frame, never in a
  URL. Standard for STOMP.

**Chosen:** STOMP CONNECT header.

### Why not push-time-only authorization?

Alternative: allow any subscription, filter events at push time by user
identity. Rejected because:
- **Push frequency >> subscribe frequency.** Filtering on every push is
  expensive.
- **Silent failure.** A client subscribed to something they can't see
  gets no feedback. Immediate rejection at subscribe time is better UX.
- **Authorization logic lives where the resource is.** Subscribing is
  the right moment to check.

### Why not SockJS-only, or WebSocket-only?

**Chosen:** WebSocket with SockJS fallback. Modern browsers support
WebSocket natively; SockJS provides fallback for older environments
(corporate proxies, embedded browsers). Both protocols share the same
STOMP semantics.

## Consequences

### Positive

- **Standard protocol.** STOMP is well-documented, well-tooled, and
  matches client libraries.
- **Per-resource granularity.** Clients subscribe only to what they need.
- **Immediate authorization feedback.** Unauthorized subscriptions are
  rejected at SUBSCRIBE, not silently ignored.
- **No persistence overhead.** Service is stateless; can scale
  horizontally.
- **Cross-replica fan-out via Redis.** Independent of the subscription
  model.

### Negative

- **No snapshot on subscribe.** Clients must fetch current state via REST
  separately.
- **Re-subscription on reconnect.** Clients must handle reconnection and
  re-subscribe. Client libraries provide hooks.
- **Two REST calls per subscription.** Ownership verification is not free.
- **Deferred user-scoped topic.** `/topic/users/{id}/notifications`
  requires a JWT `customer_id` claim or a lookup cache. Deferred to
  Phase 5.

### Neutral

- **In-memory subscription registry** limits correctness across restarts.
  Accepted trade-off.
- **STOMP heartbeat interval** must match between client and server.
  Configured consistently.

## Implementation

- STOMP endpoint: `WebSocketConfig.registerStompEndpoints`.
- Authentication: `StompAuthChannelInterceptor`.
- Subscription validation: `SubscriptionInterceptor`.
- Registry: `SubscriptionRegistry`.
- Ownership verification: `OwnershipVerifier`.
- Cross-replica fan-out: Redis pub/sub (Batch 8.4).

## Related Decisions

- **ADR-0009:** Kafka event architecture.
- User-scoped notifications, managed development `customer_id` claims and
  explicit unsubscribe tracking are implemented; see the current
  [notification contract](../../notification-service/README.md).
- Durable replay, session-expiry closure and replica/recovery acceptance remain
  open in [TASKS.md](../../TASKS.md).

## References

- STOMP specification: https://stomp.github.io/
- Spring WebSocket: https://docs.spring.io/spring-framework/reference/web/websocket.html
- SockJS: https://github.com/sockjs/sockjs-client
