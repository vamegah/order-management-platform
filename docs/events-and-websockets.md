# Durable events and authorized real-time delivery

Business state and source events use PostgreSQL transactional outboxes. Notification consumers use a transactional inbox and a separate dispatch queue. Kafka, database commits, and HTTP recipients do not share one transaction: publication is at least once, with stable identities and consumer deduplication. Redis and STOMP carry transient invalidation hints. Clients read canonical APIs after reconnect and periodically while connected.

## Event contract and compatibility

The version-one envelope is published in `contracts/events/envelope-v1.schema.json`, with an immutable published schema and order, inventory, and payment fixtures. New shared outbox writes include `eventId`, `eventType`, `aggregateId`, `occurredAt`, `schemaVersion`, and `data`. They also preserve the original flat v1 payload fields so existing consumers can ignore the additive envelope. Historical flat records and documented dotted payment/order/inventory aliases remain readable. The REST payment writer already emits the same envelope; aliases normalize to code-owned event classes for live delivery.

Event, order, payment, reservation and customer identities are canonical UUIDs. Warehouse identities preserve existing bounded codes such as `WH-1`. Timestamps are ISO instants, and aggregate/payload identities and Kafka topic categories must agree. Supported version is 1. The boundary rejects unknown versions/types, malformed JSON, missing routing identities, and oversized input before inserting inbox or dispatch state. Unknown additive fields are allowed. Topic names are `orders.events.order`, `inventory.events.reservation`, and `payments.events.payment`; arbitrary topic remapping is not supported by this contract.

The required contract CI job validates fixtures and compares v1 with both its published schema and the exact PR base. Java consumer tests cover flat/enveloped and additive old/new combinations. An incompatible schema requires a separate version and a consumer migration; rewriting the published v1 snapshot is rejected.

The Kafka key is the aggregate identity. New outbox sequence numbers establish publication order within each topic/aggregate. A pending or failed predecessor blocks later events for that aggregate while unrelated aggregates drain. Consumers must not infer state-transition history from STOMP delivery order: reconnects, replay, uncertain acknowledgments, and live transport disruption can cause duplicates or gaps. Optional `aggregateVersion` and `traceparent` fields are reserved for additive producers; consumers never use optional metadata as authorization.

## Producer inventory and outbox recovery

| Producer | State changes | Durable path |
| --- | --- | --- |
| Order service | Creation, placement, confirmation, shipment, delivery, cancellation | `OrderEventPublisher` → shared mandatory-transaction writer → `orders.outbox_event` |
| Inventory service | Reservation creation, commit, release, expiry, including bulk operations | `InventoryEventPublisher` → shared mandatory-transaction writer → `inventory.outbox_event` |
| Payment gRPC | Intent and payment transitions | `PaymentEventPublisher` → shared mandatory-transaction writer → `payments.outbox_event` |
| Payment REST/provider/refund flows | Charges, provider completion/failure, refunds and reconciliation | Payment-specific mandatory-transaction writer → `payments.outbox_event` |

The local after-commit gRPC stream callbacks are ephemeral hints; the corresponding domain event still uses the outbox. Product/customer services have no domain-event producer in the current contract. Legacy shared direct-publisher helpers are not the business event path.

Drainers hold bounded `FOR UPDATE SKIP LOCKED` row locks through broker acknowledgment and database commit. A lost connection or process releases those locks; no independent expiring lease is necessary. A crash after broker acknowledgment can leave a row pending and deliberately cause redelivery. Failed attempts remain pending until the configured attempt bound, then become `FAILED`. Failed/pending rows are never removed by retention cleanup. Published source records use `omp.outbox.published-retention`; notification dispatch rows retain published work for seven days.

Monitor `outbox.pending`, `outbox.failed`, `outbox.oldest.pending.seconds`, and `outbox.drainer.errors.total`, plus notification dispatch pending/oldest/failure metrics. Failure diagnostics contain bounded codes rather than broker exception text or payloads.

Recover a source row only after fixing its underlying fault. Use a named database operator login, the owning service schema, and least-privilege update/insert permissions. Set connection details through `PGSERVICE`/the PostgreSQL environment; do not put passwords in command history.

```sh
PGOPTIONS='-c search_path=orders,public' psql -X --set=ON_ERROR_STOP=1 \
  --set=id=00000000-0000-0000-0000-000000000001 \
  --set=reason='Broker restored; incident reviewed' -f scripts/outbox-replay.sql
```

Use `inventory` or `payments` for the corresponding service. The script changes only `FAILED` to `PENDING` and records the database session identity, reason, time, and row ID in one transaction. Missing/non-failed rows exit with status 3. Concurrent attempts cannot both reset the same failed state. Published rows must not be reset during normal operations. The disposable acceptance harness deliberately resets acknowledged rows to model an uncertain outcome and checks deduplication.

## Consumer quarantine and dispatch

The `live` and `webhook` consumers have separate `(consumer_id, event_id)` inbox identities. An inbox insertion and all matching dispatch inserts share one database transaction. Identical redelivery produces no extra handoff; reuse of an event ID with different bytes is quarantined. Kafka offsets commit only after the transactional method returns. Retained topics are read from the earliest available offset for a new group.

Live work becomes a Redis dispatch. Webhook matching, retries, and operator replay become Kafka dispatches, with a database due time for delayed retries. Workers no longer sleep on a partition until a retry is due. A bounded drainer waits for Kafka acknowledgment, or Redis publication, before marking a dispatch published. Unknown acknowledgment outcomes can redeliver with the original event/delivery identity. External webhook receivers must deduplicate `X-Webhook-Delivery-Id`; a remote HTTP response and a local database transaction cannot establish exactly-once receipt.

Domain, delivery, and delivery-DLQ listeners use bounded retries and durable quarantine. Contract failures skip transient retries. An unavailable quarantine database causes recovery to throw, preserving the source offset for another attempt. A later valid record can proceed after durable quarantine. Source topic/partition/offset, consumer identity, payload hash, and safe failure code are retained. Raw payloads stay in the protected database and are excluded from list/inspection responses and logs.

Only administrators can call `/api/v1/event-recovery`:

- `GET /quarantine?limit=50&after=0&includeReplayed=false` lists at most 200 records. Continue using the last `cursor`; restarting a scan also finds later arrivals. Replayed records are hidden by default.
- `GET /quarantine/{id}` inspects safe diagnostic metadata.
- `POST /quarantine/{id}/replay` accepts a required bounded `reason` and optional `correctedPayload`. Supported, parseable domain identities and ownership metadata cannot be changed by correction. An unreadable source requires an explicitly reviewed replacement. Replay revalidates the contract and atomically records handling and audit metadata.
- `GET /dispatches` lists bounded failed handoffs. `POST /dispatches/{id}/retry` resets only failed work and requires an audited reason.

Quarantine replay locks the record; repeated/concurrent replay yields conflict and cannot duplicate the inbox effect. Delivery messages are revalidated before replay. Webhook delivery-DLQ replay also locks its original row and durably enqueues the original delivery ID before marking it replayed. A delivery advisory lock prevents concurrent workers from posting the same identity simultaneously.

Unresolved quarantine and dispatch work is retained. Replayed quarantine payloads expire after 90 days; compact inbox identities and recovery audit records are not automatically removed. Archive them only with a coordinated, reviewed replay-horizon policy. Removing deduplication identities while older Kafka/outbox data remains replayable changes the guarantee.

## WebSocket trust and limits

CONNECT requires a signed, unexpired issuer JWT with a stable subject, valid managed customer claim, and a signed original issue-to-expiry lifetime of at most five minutes. Tokens without an issue timestamp and old long-lived tokens in their final minutes are rejected. Refresh credentials by reconnecting. Order and payment subscriptions forward the caller's JWT to canonical ownership APIs, with 2-second connection and 3-second read limits. Positive resource proofs expire after 30 seconds and are bounded to 4,096 entries and 64 distinct in-flight lookups. Identical concurrent requests share one lookup. Keys include the signed-token digest, subject, verified customer, resource and type, so token/identity changes cannot reuse a prior proof. Failed checks are never cached; resource events invalidate proofs before dispatch. The signed customer claim avoids the customer-profile lookup. Resource owners are immutable in the current order/payment contracts. A future transfer feature must invalidate active subscriptions as part of its transaction/revocation design.

Inventory topics require administrator authority. `/user/queue/notifications` routes by stable issuer subject and verified customer identity. Subscription IDs are scoped to a session, independently removable, and cleaned on disconnect. Clients cannot SEND application messages. A session holds at most 100 subscriptions and each replica caps concurrent sockets through `omp.notifications.max-concurrent-clients`.

Idle token expiry runs on the dedicated WebSocket scheduler rather than the dispatch scheduler. Expired sessions lose their registry entries and actual sockets close. The outbound channel independently checks expiry before delivering each message. Token revocation stops new credentials at the issuer; an already-issued JWT grants its claims only until its expiry, at most five minutes. Idle socket closure follows on the one-second sweep. There is no unbounded ownership cache or claim that offline JWT verification provides immediate issuer logout notification.

Per-session message/buffer/send limits are 64 KiB input, 256 KiB outbound buffering, and 20 seconds sending, with a 30-second first-message deadline. Inbound/outbound executor queues are bounded at 2,048/4,096 tasks and at most eight threads. HTTP connector pools use at most 32 workers (four spare), with a 128-connection accept backlog, so upgrades cannot expand the default 200-thread pools under pressure. Slow consumers disconnect and must reconnect/resync. Redis and replica disruption can drop live hints; durable business state and canonical reads remain authoritative.

## Reproduce the declared acceptance

`docs/event-delivery-slos.json` declares the topology, event rate, duration, latency/delivery objectives, and recovery limits before measurement. The opt-in overlay exposes two notification replicas only on loopback ports 18084/18087 and management ports 18085/18088. The acceptance replicas use the existing production limits of 1 GiB and two CPUs each, with a 256 MiB G1 heap and bounded native reservations. The initial 512 MiB development-sized runs paged heavily and failed setup/sustained delivery; they do not establish capacity at that smaller size. The development realm includes an eight-second test client for expiry/refresh acceptance; do not import development identities into production.

```sh
export COMPOSE_PROJECT_NAME=omp-events-acceptance-local0001
export E2E_EVENTS_CONTROLS=true
bash scripts/ci-setup.sh
# Build the six service images, then start the isolated overlay and all source dependencies.
docker compose --env-file .env.example -f docker-compose.yml -f docker-compose.e2e.yml \
  -f docker-compose.events-e2e.yml up -d --build --wait traefik product-service \
  customer-service order-service inventory-service payment-service notification-service notification-service-2
cd clients/notifications
npm ci --ignore-scripts
npm run build
node test/realtime-load.mjs
```

Use a fresh disposable project and the routing/key-permission preparation in the CI job. The harness checks project-name and container-label ownership before every process or infrastructure control. It exercises all three producer process crashes during broker outage, source replay, poison quarantine and concurrent administrator replay, 1,000 healthy clients across two replicas, foreign private listeners, sustained delivery/latency, real order/payment denial, short-lived token expiry/refresh, paused slow readers with fresh random 60,000-character payloads and compression disabled, a reconnect storm, Redis interruption, replica replacement, and 1,000 canonical state reads.

The slow-reader fixture disables its own incoming-heartbeat watchdog; closure must come from server/network backpressure. Fresh random payloads avoid compression/context reuse hiding the intended pressure. Positive private listeners on both replicas receive every steady event while foreign private listeners receive none.

The required `events-e2e` job retains measured JSON, JUnit, topology/resource diagnostics, and service logs under `coverage/events-acceptance`. Its quality-gate dependency rejects missing, skipped, incomplete, or failed phases. This reference SLO applies to the declared topology/hardware and is not a general production capacity claim. Keep the existing supported TypeScript client tests and deployed convergence tests in addition to the load harness.
