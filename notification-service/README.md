# Notification Service

Real-time push service. Consumes Kafka events from orders, inventory, and
payments, and pushes updates to subscribed WebSocket clients.

Part of the [Order Management Platform](../README.md).

## Overview

notification-service is a **WebSocket + Kafka** service. Clients connect
over STOMP-over-WebSocket, subscribe to destinations, and receive live
updates as state changes propagate through Kafka.

WebSocket subscriptions are in memory and tied to session lifetimes. Outbound
webhook subscriptions and delivery state are stored in PostgreSQL. Redis pub/sub
handles cross-replica live fan-out; it does not retain disconnected clients' events.

## Endpoints

| Protocol | Port | Path | Purpose |
|----------|------|------|---------|
| WebSocket (STOMP) | 8084 | `/ws` | Client connections |
| HTTP | 8085 | `/actuator/*` | Management endpoints |

The application HTTP server (port 8084) also exposes authenticated webhook
subscription management and delivery history APIs. Administrator DLQ operations
use `/api/v1/webhook-dlq`. See [verification scope and commands](../docs/notification-verification.md)
for tested behavior and remaining acceptance work.

## Subscription Model

Clients connect to `/ws` over STOMP, authenticate on CONNECT, then
subscribe to destinations:

| Destination | Resource | Who can subscribe |
|-------------|----------|-------------------|
| `/topic/orders/{orderId}` | Order | Order owner or admin |
| `/topic/payments/{paymentId}` | Payment | Payment owner or admin |
| `/topic/inventory/{sku}` | SKU | Admin only |
| `/user/queue/notifications` | Customer order/payment events | Authenticated customer's own events |

The private queue derives its customer identity from the verified JWT or
customer-service `/me`. Clients cannot choose another user's private destination.
Routing uses the immutable JWT subject, never a display name. Client SEND frames
are rejected; this service only accepts subscriptions. STOMP subscription IDs are
scoped to their session. UNSUBSCRIBE removes just that ID; disconnect removes all
subscriptions for that session. Reusing an active subscription ID is rejected.

### Initial Snapshot

When a client subscribes, they don't receive a snapshot of the current
state. The client is expected to:
1. Fetch current state via the owning service's REST API (e.g., `GET /api/v1/orders/{id}`).
2. Subscribe to the WebSocket destination.
3. Apply incoming events as they arrive.

If a state change occurs between fetch and subscribe, the next event
captures it. If a change occurs **during** subscribe (race), the event
might be missed — but the client's next fetch would catch up. Documented
behavior; clients should periodically re-sync via REST.

## Authentication

Clients send the JWT on the STOMP CONNECT frame:
CONNECT
Authorization: Bearer <jwt>
heart-beat: 10000,10000


The server validates the JWT against Keycloak's JWKS endpoint. On
success, sends a CONNECTED frame and attaches the session's Principal.
On failure, sends an ERROR frame and closes.

**Why not the HTTP handshake?** Browsers can't set custom headers on the
WebSocket upgrade. The STOMP CONNECT frame is the standard place for
authentication in this protocol.

## Authorization

- **Order subscriptions:** ownership is checked through order-service at SUBSCRIBE.
- **Payment subscriptions:** ownership is checked through payment-service's HTTP API.
- Both use the verified `customer_id` claim when present, otherwise customer-service
  `/me`; the caller's JWT is relayed to downstream services. Lookup failures deny
  registration. Administrators may subscribe to order/payment resource topics.
- Payment-service requires a `customer_id` claim for customer HTTP access. Configure
  the issuer to provision this UUID as an administrator-managed attribute; the
  `/me` fallback does not replace that downstream requirement. Users must not be
  allowed to edit their own ownership claim. A fresh bundled development realm
  provisions Alice's managed claim, the default `basic` subject mapper and
  explicit role mappers; existing realms
  need an administrative migration. See [external setup](../docs/external-e2e.md).
- **Inventory subscriptions:** admin-only.

Non-admin subscriptions to destinations the user doesn't own are rejected
with a STOMP error. Subsequent commands from expired sessions are rejected;
clients should reconnect with a refreshed JWT. Existing subscriptions are not
automatically closed at token expiry. Scheduled expiry closure and immediate
revocation remain unfinished.

## Cross-Replica Fan-Out

When notification-service runs with multiple replicas, a client connected
to replica A must receive events triggered by Kafka consumption on
replica B.

**Mechanism:** Redis pub/sub.
1. Any replica that consumes a Kafka event publishes it to a Redis channel.
2. All replicas subscribe to that channel.
3. Each replica dispatches the event to its local subscribers.

Kafka consumers and Redis subscribers implement this flow. Multi-replica load
and recovery acceptance remain open.

## Configuration

| Variable | Default (dev) | Purpose |
|----------|---------------|---------|
| `SERVER_PORT` | `8084` | WebSocket HTTP port |
| `MANAGEMENT_PORT` | `8085` | Actuator HTTP port |
| `REDIS_HOST` | `redis` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | (from `.env`) | Redis password |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Kafka brokers |
| `OIDC_ISSUER_URI` | `http://keycloak.localhost/realms/omp` | JWT issuer |
| `OIDC_JWK_SET_URI` | `http://keycloak:8080/...` | JWKS endpoint |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4317` | Trace exporter |
| `PROMETHEUS_ENV` | `dev` | Metrics environment label |
| `CUSTOMER_SERVICE_URL` | `http://localhost:8082` | Ownership identity fallback |
| `ORDER_SERVICE_URL` | `http://localhost:8083` | Order ownership reads |
| `PAYMENT_SERVICE_URL` | `http://localhost:8080` | Payment ownership reads (application HTTP port) |
| `POSTGRES_HOST` | `localhost` | Outbound webhook database host |
| `POSTGRES_PORT` | `5432` | Outbound webhook database port |
| `NOTIFICATION_SERVICE_DB_USER` | `notifications_svc` | Database role |
| `NOTIFICATION_SERVICE_DB_PASSWORD` | local development password | Database password |

**Service-specific configuration** (`application.yml`, `omp.notifications.*`):
- `endpoint-path` — STOMP endpoint path (default `/ws`)
- `allowed-origins` — CORS origins for the handshake
- `heartbeat-interval-ms` — STOMP heartbeat interval (default 10000)
- `max-concurrent-clients` — connection limit (default 10000)
- `max-message-size-bytes` — STOMP message size limit (default 65536)

## Development

```bash
# Build
./mvnw -pl notification-service -am clean package

# Run with PostgreSQL, Kafka, Redis and the configured JWT issuer available
java -jar notification-service/target/notification-service-1.0.0-SNAPSHOT-exec.jar
```

Run `./mvnw -pl notification-service -am clean verify` with Docker available for
the PostgreSQL/Redis integration suites and the existing coverage gate. See
[notification verification](../docs/notification-verification.md) for fixture
boundaries and the full test scope.

### Testing with a STOMP client
The easiest way to interact with the service is via a JavaScript STOMP
client in a browser:

```javascript
import { Client } from '@stomp/stompjs';

const client = new Client({
  brokerURL: 'ws://notification.localhost/ws',
  connectHeaders: {
    Authorization: 'Bearer ' + jwt
  },
  onConnect: () => {
    client.subscribe('/topic/orders/00000000-0000-0000-0000-000000000301', (msg) => {
      console.log('Order update:', JSON.parse(msg.body));
    });
  }
});

client.activate();
```
Matching events flow through Kafka and Redis to authorized active subscriptions.

## Observability
Metrics: /actuator/prometheus (port 8085). Custom metrics:

websocket_connections_active — current connections

websocket_connections_total — total connections since start

websocket_disconnects_total — total disconnects

websocket_subscriptions_active — current subscriptions

websocket_sessions_active — sessions with at least one subscription

Logs: JSON to stdout (Loki).

Traces: OTLP to the collector; the WebSocket handshake and each
STOMP frame produce spans.

Dashboard: OMP — Notification Service (Batch 8.6).

## Event Pipeline

notification-service bridges Kafka events to WebSocket clients:
Service (order/inventory/payment)
│
│ publishes event
▼
Kafka topic (orders.events.order, etc.)
│
│ consumed by one notification-service replica (Kafka consumer group)
▼
Redis channel (notifications:orders, etc.)
│
│ broadcast to ALL replicas (Redis pub/sub)
▼
Local SubscriptionRegistry lookup
│
│ fan-out to matching subscriptions
▼
WebSocket clients (via STOMP)


**Why Redis in the middle?** Kafka's consumer group delivers each event to
exactly one replica. If a client is connected to a different replica, that
replica wouldn't see the event. Redis pub/sub broadcasts to all replicas,
ensuring delivery to every subscribed client regardless of which replica
they're connected to.

**Data flow characteristics:**
- **Kafka consumption:** manual offset commit after Redis publish. At-least-once delivery.
- **Redis pub/sub:** fire-and-forget. If a replica is down when an event is published, that replica misses it (clients re-sync via REST on reconnect).
- **WebSocket dispatch:** best-effort. Slow clients' messages queue briefly, then drop (Phase 8.5 backpressure).

## Known limitations

Live WebSocket messages are not persisted for disconnected or slow clients;
clients must re-sync via REST on reconnect. This differs from outbound webhook
delivery state, which is persisted in PostgreSQL.

No snapshot on subscribe. Client must fetch current state via REST.

Scheduled closure of expired JWT sessions, multi-replica load/recovery tests,
invalid-event quarantine and outbound URL/retry hardening remain open in
[TASKS.md](../TASKS.md).

Related Documentation
Platform README

ADR-0009: Kafka event architecture

ADR-0010: WebSocket subscription model

Spring WebSocket documentation

STOMP specification
