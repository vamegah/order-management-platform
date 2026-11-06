# Shared events

The module supplies canonical event records, JSON serialization, tolerant envelope
reading, Kafka topic declarations and the transactional outbox. It owns no service
schema or business state.

## Wire contracts

| Topic | Aggregate key | Canonical records |
|-------|---------------|-------------------|
| `orders.events.order` | order ID | Created, placed, confirmed, shipped, delivered, cancelled |
| `inventory.events.reservation` | reservation ID | Created, committed, released, expired |
| `payments.events.payment` | payment ID | Intent created, confirmed, cancelled, failed, refunded, partially refunded |

Every canonical `BaseEvent` has an event ID/type, ISO timestamp, schema version 1
and explicit `aggregateId`. Concrete records carry order/reservation/payment data.
Adding `aggregateId` to serialized canonical records is additive; the reader can
also derive it from older flat records' domain IDs.

`EventSerde` serializes and deserializes the sixteen canonical record types through
a fixed `EventRegistry` map. It does not accept Java class names or enable Jackson
default typing. Null Kafka tombstones round-trip as null. Unknown types, incomplete
metadata, malformed JSON and unsupported schema versions raise Kafka
`SerializationException`. Additive JSON fields are ignored. Instants use ISO strings;
null record fields are omitted.

`DomainEventReader` also accepts the existing envelope form used by payment REST
and outbound webhook consumers:

```json
{
  "eventId": "evt-1",
  "eventType": "payment.confirmed",
  "aggregateId": "pay-1",
  "occurredAt": "2026-10-29T12:00:00Z",
  "schemaVersion": 1,
  "data": {"paymentId": "pay-1", "orderId": "order-1"}
}
```

It supports legacy `id`/`type`/`timestamp` metadata aliases and flat bodies.
Identity must be nonblank text; `data`, when present, must be an object; supplied
schema versions must be positive integers. Missing legacy schema versions default
to 1. Future unknown types can still be read for consumer-side registry decisions.
Dotted-name aliases are envelope contracts, not interchangeable typed record names.

## Durable production

Write the outbox row inside the transaction that changes the aggregate:

```java
@Transactional
public void placeOrder(Order order) {
    order.place("customer");
    orderRepository.save(order);
    outboxWriter.write(OrderEventFactory.placed(order, "customer"),
            KafkaTopics.ORDERS_EVENTS_ORDER);
}
```

This example uses application-owned order/factory types. The shared writer requires
an active read-write transaction, including direct calls without a Spring proxy.
It validates required metadata/storage bounds before repository writes.
Producing services must register the shared outbox entity/repository and own a
Flyway migration based on `src/main/resources/outbox-schema.sql`. They must enable
scheduling and transaction management. The auto-configuration activates only when
an outbox repository is registered. Disabling draining keeps the writer available.

```yaml
omp:
  outbox:
    enabled: true
    poll-interval: 1s
    batch-size: 100
    max-attempts: 10
    publish-timeout: 5s
    published-retention: 7d
    cleanup-interval: 1h
```

Batch size must be 1..1000, attempts positive, and durations at least 1ms.
The acknowledgement future has a finite timeout; Kafka's own producer settings
also bound synchronous metadata/buffer waits. Publish failures increment attempts
until FAILED. Interruption is restored and leaves the remainder of the batch
pending. Published rows cannot be changed to failed by a late failure call;
repeated publication marking preserves the original publication timestamp.

Draining uses row locks and SKIP LOCKED within a transaction. Delivery remains
at-least-once: a lost acknowledgement or crash after Kafka delivery can cause a
retry with the same event ID. Consumers must deduplicate. This module does not
provide exactly-once delivery, global ordering across concurrent workers or an
operator replay endpoint. Cleanup deletes only old PUBLISHED rows.

Metrics include `outbox.pending` (PENDING + FAILED), `outbox.failed`,
`outbox.published.total` (acknowledged sends) and `outbox.drainer.errors.total`.
The legacy `KafkaEventPublisher` now waits for acknowledgement and reports future
failures/timeouts to its caller, but remains non-durable. Use the outbox for
state-changing domain events; after-commit callback failures cannot undo a commit.

## Topic provisioning

`TopicProvisioner` is now registered through Boot auto-configuration. It declares
the three domain topics through the application's existing `KafkaAdmin`, preserving
its bootstrap, TLS, SASL, timeout and fail-fast configuration. It does not create a
second client with only a bootstrap address. KafkaAdmin manages creation and
supported existing-topic adjustments, including partition increases; set
`omp.kafka.provision-topics=false` for operator-managed topics.

```yaml
omp:
  kafka:
    provision-topics: true
    default-partitions: 3
    default-replication-factor: 1
    retention-hours: 168
    min-insync-replicas: 1
```

Invalid partitions, replication, retention or ISR settings fail before broker
calls. See [Spring Kafka topic configuration](https://docs.spring.io/spring-kafka/reference/kafka/configuring-topics.html).

## Verification

```sh
./mvnw -pl shared/events -am clean verify
```

Tests cover canonical round-trips, additive payloads, malformed input, outbox
transaction guards, acknowledgement timeout/retry, interruption, terminal states,
configuration and topic registration. JaCoCo enforces 80% instruction coverage with
no production-class exclusions. Broker/database deployment tests remain separate.
