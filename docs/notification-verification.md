# Notification verification scope

The notification application stores outbound subscriptions, delivery attempts
and dead letters in PostgreSQL. Its transport bean has a distinct name from the
webhook sender component, and subscription owners use an explicit converter for
the existing lowercase database values. Historical Flyway migrations are
unchanged.

The full application integration fixture uses Spring-owned PostgreSQL 16.4 and
Redis 7.4 containers, embedded Kafka, random application/management ports and a
test JWT decoder. Each class closes its context before stopping infrastructure.
Webhook topics retain three replicas by default; the single-broker test profile
explicitly uses one. Run with Docker available:

```sh
./mvnw -pl notification-service -am clean verify
```

- `KafkaConsumerIT` checks a uniquely identified order event from Kafka through
  Redis and removes its temporary listener afterward.
- `RedisFanoutIT` verifies dispatch of the exact event published to real Redis.
- `WebSocketSubscriptionIT` connects through the production STOMP authentication,
  subscription and dispatch components. It waits for registration, checks the
  delivered event/resource and verifies explicit unsubscribe/disconnect cleanup.
  The external order ownership lookup is mocked; this is not deployed
  order-service or issuer acceptance.
- `WebhookDeliveryEndToEndIT` publishes to Kafka, receives a signed HTTP request
  at a local subscriber and verifies its successful PostgreSQL audit record.
  It independently recomputes HMAC over the received UTF-8 body and timestamp,
  and checks committed event/delivery consumer offsets. The subscription is
  registered through the repository, rather than through HTTP. A separate case
  returns HTTP 410, checks the persisted failure/DLQ and owner-scoped history,
  replays through the administrator API, verifies a successful retry with the
  original delivery ID, and checks duplicate/missing replay responses (409/404).
- `WebhookManagementIT` exercises the real HTTP security chain and committed
  database state: creation, owner isolation, updates, secret rotation,
  disable/enable, nested Keycloak administrator roles, DLQ access denial and
  invalid pagination. It uses local test tokens, not a deployed identity issuer.
- Delivery-worker, publisher, transport and retry unit contracts cover recorded
  outcomes, duplicate successful delivery, missing/disabled subscriptions,
  exhausted retries, status/transport classification, retry identity/jitter,
  broker rejection, bounded acknowledgment waits, database failures and thread
  interruption. DLQ regressions also ensure failed broker publication or corrupt
  payloads do not mark an entry replayed. Their network/repository collaborators
  are test doubles.

Webhook listeners commit each record after successful processing. Fan-out and
retry publication wait up to ten seconds for Kafka acknowledgment; broker
failures propagate to the consuming listener. DLQ database failures likewise
propagate instead of being swallowed. Invalid-event quarantine, persistent
retry scheduling, concurrent deduplication, atomic database/broker handoff,
payload enrichment, subscriber URL protection and deployed recovery remain
separate backlog work. This verification does not establish those properties.

The existing 80% notification instruction gate is preserved. Exact executed
counts, coverage results and evidence are recorded in the
[implementation report](task-implementation-report.md). Full-reactor, platform,
e2e and release acceptance remain open until their own checks pass.

Latest focused acceptance: the clean four-module run with `-Pstatic-analysis`
passes 178 unit/in-process tests (82 notification), all thirteen integration
cases and all three SpotBugs checks. There are no failures, errors, skips or
missing suites. Notification's existing instruction gate passes at 85.67%.
