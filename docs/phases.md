# Phase Plan

> **Status:** Implementation present; release acceptance incomplete
> **Last reviewed:** 2026-11-06

This document retains the original phase scope and exit targets. Implemented
services are described below; unchecked exit targets require evidence and do
not imply that the corresponding code is absent. [TASKS.md](../TASKS.md) and
[the implementation report](task-implementation-report.md) are the current
backlog and verification record. Earlier infrastructure completion claims have
been reopened for fresh deployment verification.

## Overview

The Order Management Platform is built in **eight phases**. Each phase
delivers a coherent slice of functionality and leaves the repository in a
runnable, tested, documented state. This document defines what each phase
delivers, how we know it's done, and how phases relate to each other.

The structure reflects a deliberate ordering: **infrastructure first, then
external APIs, then internal communication, then event-driven patterns,
then legacy integration, then hardening.** Each phase builds on what came
before.

---

## Phase Summary

| Phase | Name | Focus | Status |
|-------|------|-------|--------|
| **0** | Infrastructure | Containers, configs, observability | Configured; deployment/operations acceptance incomplete |
| 1 | REST APIs | Product, customer, order services | Implemented; lifecycle and public-contract acceptance incomplete |
| 2 | GraphQL | Query gateway over REST services | Implemented; full consumer-contract acceptance incomplete |
| 3 | gRPC | Internal service-to-service communication | Implemented; streaming/rotation acceptance incomplete |
| 4 | WebSocket | Real-time notifications | Implemented; replica/recovery/load acceptance incomplete |
| 5 | Webhooks | Event notifications, payment integration | Implemented; security/recovery/load work remains |
| 6 | SOAP | Legacy banking adapter | Implemented; deployed TLS/resilience acceptance incomplete |
| 7 | Hardening | Alerting, sampling, tracing polish | Partially configured; release acceptance incomplete |

---

## Phase 0 — Infrastructure

> **Status:** Configured; fresh deployment and operations acceptance incomplete

### Goal

Provide a complete, runnable, observable infrastructure stack that
business services can plug into without modifying. Every backing service
(Postgres, Redis, Kafka), every cross-cutting concern (auth, ingress,
observability), and every deployment path (dev, prod) is configured
before any business logic exists.

### Deliverables

- **Compose stack (dev + prod overlay):** 12 services running
  cleanly on a laptop with 6 GB of RAM.
- **Postgres:** schemas, roles, extensions, tuning for dev and prod,
  WAL archiving, pgBackRest backups.
- **Keycloak:** `omp` realm with clients, roles, test users.
- **Traefik:** dev and prod static configs, security headers
  middleware, TLS via Let's Encrypt (prod).
- **Prometheus:** scrape config, baseline alerts, file-based service
  discovery placeholder.
- **Grafana:** Prometheus/Loki/Tempo datasources, dashboard provider,
  starting "OMP — Overview" dashboard.
- **Loki + Promtail:** log aggregation, Docker log shipping, trace
  correlation via structured metadata.
- **Tempo:** trace storage, OTLP receivers, retention.
- **OTel Collector:** OTLP trace pipeline to Tempo.
- **Documentation:** top-level README, docs structure, phases plan
  (this file).

### Exit Criteria

- [ ] All configured services start cleanly using the documented Compose files.
- [ ] Every service passes its Docker healthcheck within the documented startup budget.
- [ ] Grafana shows "OMP — Overview" with green scrape targets.
- [ ] Prometheus `/targets` shows all configured targets as `UP`.
- [ ] Keycloak realm imports; test users (`alice`, `admin`) can log in.
- [ ] `docker compose -f docker-compose.yml -f docker-compose.prod.yml config` validates.
- [ ] Production credentials are supplied outside committed development fixtures.
- [ ] Every `${VAR}` in compose files has a corresponding entry in
  `.env.example`.

### Key Decisions (see `adr/` — Phase 1+)

- Schema-per-service in a single Postgres instance.
- Two compose files (dev + prod overlay).
- Promtail for log shipping (not OTel Collector).
- Traefik as ingress for both dev and prod.
- OTel Collector handles traces only.

### Enables

- **All subsequent phases.** Business services assume Postgres,
  Redis, Kafka, Keycloak, and observability exist.

---

## Phase 1 — REST APIs

> **Status:** Implemented; acceptance incomplete
>
> **Depends on:** Phase 0

### Goal

Deliver the three foundational REST services that form the platform's
business core: **product**, **customer**, and **order**. Each is a
Spring Boot application with its own schema, JWT authentication, and
full REST Assured test coverage.

### Deliverables

- **Three Spring Boot services** (Java 21, Spring Boot 3.3):
  - `product-service` — product catalog, pricing.
  - `customer-service` — customer profiles, preferences.
  - `order-service` — order creation, status, history.
- **OpenAPI 3.1 specs** for each service (design-first, verified in CI).
- **Flyway migrations** for schema management.
- **JWT authentication** via Spring Security + Keycloak.
- **REST Assured integration tests** for every endpoint.
- **Contract tests** (schema validation with JSON Schema).
- **Per-service Prometheus scrape targets** via `file_sd_configs`.
- **Per-service Grafana dashboards** (RED metrics: Rate, Errors,
  Duration).
- **Tier mapping** added to Promtail config for the new services.
- **ADR-0001 through ADR-0003** documenting phase-level decisions.

### Exit Criteria

- [ ] All three services respond `200` on `/actuator/health`.
- [ ] `./mvnw verify` passes for the entire reactor.
- [ ] Test coverage ≥ 80% per module (enforced by JaCoCo).
- [ ] Every endpoint is documented in OpenAPI and reachable via
  Traefik (`http://<service>.localhost`).
- [ ] JWT validation works: requests with valid tokens succeed,
  requests without fail with `401`.
- [ ] All three services appear in Prometheus `/targets` as `UP`.
- [ ] Grafana dashboards show per-service RED metrics.
- [ ] Traces for cross-service calls appear in Tempo.

### Key Decisions to Make

- **Idempotency keys:** which header (`Idempotency-Key`), and how to
  store them (dedicated table vs. Postgres unique index).
- **Pagination:** cursor-based vs. offset; where to encode cursors.
- **Error format:** RFC 7807 Problem Details for all errors.
- **Versioning:** URL-based (`/api/v1/`) vs. header-based.
- **HATEOAS:** include links or not (mildly controversial).

### Risks

- **Keycloak integration complexity.** Mitigation: implement JWT
  validation in one service first, then replicate.
- **Flyway + multiple schemas.** Mitigation: one Flyway history table
  per schema; each service runs its own migrations.
- **Cross-service reads.** Order-service reads customer/product via
  REST, not database joins. Mitigation: keep reads limited and cache
  aggressively.

### Enables

- Phase 2 (GraphQL sits on top of these services).
- Phase 3 (gRPC can be added between them).
- Phase 4 (WebSocket notifications triggered by order events).

---

## Phase 2 — GraphQL

> **Status:** Implemented; acceptance incomplete
>
> **Depends on:** Phase 1

### Goal

Add a GraphQL gateway that aggregates the REST services into a single,
flexible query surface. Demonstrate the "different clients need
different fields" use case: a mobile app can query 5 fields; a web
dashboard, 30.

### Deliverables

- **GraphQL gateway service** (`graphql-gateway`) using Spring
  GraphQL.
- **Schema** (`schema.graphqls`) covering customers, products, and
  orders.
- **Resolvers** calling the REST services via Spring's `RestClient`.
- **DataLoader** to prevent N+1 queries.
- **Query depth limit** (max 10) and complexity scoring.
- **Field-level authorization** (`@PreAuthorize` on resolvers).
- **Cursor-based pagination** for list fields (Relay spec).
- **Subscriptions** scaffolded (full support in Phase 4).
- **ADR-0004** documenting GraphQL gateway decisions.

### Exit Criteria

- [ ] GraphQL endpoint responds at `http://graphql.localhost/graphql`.
- [ ] A single query fetches customer + orders + products in one
  round trip.
- [ ] DataLoader is verified (N+1 eliminated via query counting in
  tests).
- [ ] Depth limit rejects nested queries beyond 10 levels.
- [ ] Complexity limit rejects queries scoring above threshold.
- [ ] Authorization tests verify field-level access control.
- [ ] GraphiQL playground is accessible in dev.

### Key Decisions to Make

- **Schema design:** strict schema-first (SDL file) vs. code-first.
- **Error handling:** GraphQL errors vs. HTTP errors; partial
  responses.
- **Caching:** response cache keyed by query hash? (Aggressive but
  can backfire.)
- **Federation:** standalone gateway vs. Apollo Federation (rejected
  for Phase 2 — one gateway is simpler).

### Risks

- **N+1 queries in resolvers.** Mitigation: DataLoader for every list
  field; verified by tests.
- **Query complexity attacks.** Mitigation: depth + complexity limits
  enforced by `graphql-java` instrumentation.
- **Schema drift with REST.** Mitigation: contract tests between
  gateway and services.

### Enables

- Phase 7 (dashboard-level queries use GraphQL).
- Future admin UIs (rich data needs).

---

## Phase 3 — gRPC

> **Status:** Implemented; acceptance incomplete
>
> **Depends on:** Phase 1

### Goal

Introduce gRPC for internal service-to-service communication. Replace
some REST calls between services with gRPC to demonstrate the
performance and type-safety benefits. Add `inventory-service` and
`payment-service` as gRPC-only internal services.

### Deliverables

- **Two new services:** `inventory-service`, `payment-service`.
- **Proto definitions** for all internal RPCs.
- **Code generation** via `protobuf-maven-plugin`.
- **Unary, server-streaming, client-streaming, bidirectional RPCs**
  (at least one of each, to demonstrate the patterns).
- **mTLS between services** (in prod overlay).
- **Deadlines, retries, interceptors** for resilience.
- **gRPC status codes** mapped to application errors.
- **Service mesh or gateway pattern** for external→internal
  translation.
- **ADR-0005** documenting the internal communication strategy.

### Exit Criteria

- [ ] All internal service calls use gRPC where appropriate.
- [ ] A gRPC deadline is enforced (test with a slow endpoint).
- [ ] Streaming RPCs work end-to-end (bulk operations, price feeds).
- [ ] mTLS is enforced in prod (verified by rejecting plaintext).
- [ ] Traces include gRPC spans across service boundaries.
- [ ] Benchmarks compare REST vs. gRPC for a common operation.

### Key Decisions to Make

- **Where's the REST/gRPC boundary?** Public APIs stay REST; internal
  service-to-service moves to gRPC. Where exactly to draw this line.
- **Proto ownership:** one repo (monorepo) vs. per-service? Monorepo
  for Phase 3.
- **Streaming patterns:** which RPCs benefit; how to handle
  backpressure.
- **Retry strategy:** per-RPC deadlines; retry policy at the client
  level.

### Risks

- **Debugging complexity.** Mitigation: `grpcurl` examples in
  runbooks; gRPC-aware tracing.
- **mTLS certificate management.** Mitigation: use cert-manager-like
  patterns; document rotation.
- **Versioning protobuf.** Mitigation: additive changes only in Phase
  3; breaking-change policy documented for Phase 7.

### Enables

- Phase 4 (WebSocket notification service uses gRPC to receive
  events).
- Phase 5 (payment service integrates with webhook provider via
  gRPC-to-REST bridge).

---

## Phase 4 — WebSocket

> **Status:** Implemented; acceptance incomplete
>
> **Depends on:** Phase 1, Phase 3

### Goal

Add real-time bidirectional communication via WebSocket. Deliver a
notification service that pushes order updates to subscribed clients.

### Deliverables

- **`notification-service`** with Spring WebSocket + STOMP.
- **Topics:** `/topic/orders/{orderId}`, `/topic/payments/{paymentId}`,
  administrator stock topics and `/user/queue/notifications`.
- **JWT authentication** on CONNECT frame.
- **Redis pub/sub** for horizontal scaling (multiple notification-
  service instances fan out to all clients).
- **Heartbeats** and connection management (reconnect, backoff).
- **Kafka integration** — order events flow from order-service into
  Kafka, notification-service consumes and pushes to clients.
- **Client-side reconnection library:** [TypeScript package](../clients/notifications/README.md)
  with canonical-state reconciliation and executed transport checks.
- **Load test** demonstrating 1,000+ concurrent WebSocket clients.
- **ADR-0006** documenting WebSocket scaling strategy.

### Exit Criteria

- [ ] Client connects, authenticates, and subscribes to order topics.
- [ ] Order status changes are pushed in real time (end-to-end
  latency < 500 ms).
- [ ] Reconnection works after network blips (verified by killing
  connections mid-test).
- [ ] Two notification-service instances coordinate via Redis pub/sub.
- [ ] Load test sustains 1,000 concurrent clients with < 1% message
  loss.
- [ ] WebSocket connections appear in Prometheus metrics
  (`websocket_connections_active`).

### Key Decisions to Make

- **STOMP vs. raw WebSocket.** STOMP is easier for pub/sub; raw is
  lower overhead. STOMP for Phase 4.
- **Message format:** JSON (easy) vs. protobuf (compact). JSON for
  Phase 4.
- **Redis pub/sub vs. Kafka for fan-out.** Redis for low-latency
  pub/sub; Kafka for durability. Redis for Phase 4.
- **Subscription authorization:** can a user subscribe to another
  user's notifications? Deny by default.

### Risks

- **Sticky sessions / load balancing.** Mitigation: Redis pub/sub
  fans out to all instances; no stickiness needed.
- **Backpressure under high message rate.** Mitigation: per-client
  message queues; drop policy for slow consumers.
- **Memory leaks from stale connections.** Mitigation: idle
  timeouts; connection count metrics.

### Enables

- Phase 5 (webhook deliveries trigger WebSocket notifications to
  users).
- Phase 7 (real-time dashboards).

---

## Phase 5 — Webhooks

> **Status:** Implemented; acceptance incomplete
>
> **Depends on:** Phase 1, Phase 3

### Goal

Implement both sides of the webhook pattern. **Inbound:** receive
payment events from an external provider. **Outbound:** notify
subscribers (including customer-owned systems) when order events occur.

### Deliverables

- **Inbound webhook endpoint** in `payment-service`:
  - HMAC-SHA256 signature verification.
  - Timestamp validation.
  - Idempotency keys (event-level deduplication).
  - Async processing (return 200 immediately, process via Kafka).
- **Outbound webhook system** in `notification-service`:
  - Webhook subscription registry (API to register URLs).
  - Delivery attempt tracking with exponential backoff.
  - Dead-letter queue for repeated failures.
  - HMAC signing of outbound payloads.
  - Replay endpoint for manual redelivery.
- **Mock payment provider** for development (WireMock).
- **ADR-0007** documenting webhook security and reliability.

### Exit Criteria

- [ ] Inbound webhook accepts valid signatures, rejects invalid ones.
- [ ] Duplicate webhook deliveries don't process twice (idempotency).
- [ ] Async processing: 200 returned in < 100 ms; event processed
  asynchronously.
- [ ] Outbound webhooks deliver to registered subscribers within 5
  seconds.
- [ ] Failed deliveries retry with exponential backoff (up to 24
  hours).
- [ ] Events past retry window land in DLQ; manual replay works.
- [ ] Load test: 10,000 webhooks/second inbound sustained.

### Key Decisions to Make

- **Signature algorithm:** HMAC-SHA256 (standard). Header name:
  `X-Signature-256`.
- **Timestamp tolerance:** 5 minutes (reject older).
- **Retry schedule:** 1m, 5m, 30m, 2h, 24h (5 attempts).
- **DLQ structure:** separate Kafka topic per event type; manual
  replay tool.
- **Subscription lifecycle:** how subscribers register, update,
  delete; how to verify their endpoint works.

### Risks

- **Webhook delivery to unreliable endpoints.** Mitigation: DLQ +
  circuit breaker per subscriber.
- **Signature verification overhead.** Mitigation: HMAC-SHA256 is
  fast; no bottleneck.
- **Out-of-order events.** Mitigation: sequence numbers per
  subscription; clients responsible for ordering (documented).

### Enables

- Phase 6 (the legacy SOAP integration sends events that trigger
  outbound webhooks).
- Phase 7 (webhook delivery metrics dashboards).

---

## Phase 6 — SOAP

> **Status:** Implemented; acceptance incomplete
>
> **Depends on:** Phase 1, Phase 5

### Goal

Integrate with a legacy SOAP service to demonstrate the enterprise
boundary pattern. Deliver an **adapter** that exposes a REST facade
over a SOAP backend, isolating the rest of the platform from the SOAP
protocol.

### Deliverables

- **Legacy bank simulator** (Spring Web Services) exposing a WSDL-
  described SOAP service.
- **`banking-adapter`** service:
  - Generates client stubs from WSDL (`jaxb2-maven-plugin`).
  - Exposes REST endpoints as a facade.
  - Translates SOAP faults to RFC 7807 Problem Details.
  - Implements WS-Security (UsernameToken).
  - Adds circuit breaker (SOAP backend failures don't cascade).
- **WSDL contract tests** ensuring the adapter matches the WSDL.
- **ADR-0008** documenting SOAP adapter strategy.

### Exit Criteria

- [ ] Adapter accepts REST requests, returns REST responses.
- [ ] SOAP faults are translated to `4xx`/`5xx` Problem Details.
- [ ] WS-Security headers are correctly populated.
- [ ] Integration test verifies end-to-end: REST → adapter → SOAP
  simulator → response.
- [ ] Contract test verifies WSDL compatibility (adapter would still
  work if the WSDL changed).
- [ ] Circuit breaker opens after N consecutive SOAP failures;
  half-open state verified.

### Key Decisions to Make

- **WSDL-first vs. Java-first.** WSDL-first is standard for legacy
  integration; we don't control the WSDL.
- **SOAP version:** SOAP 1.1 vs. 1.2. Legacy systems vary; pick
  based on the simulator.
- **WS-Security profile:** UsernameToken (simplest), or signature +
  encryption.
- **Facade granularity:** one REST endpoint per SOAP operation, or
  aggregated endpoints.
- **Caching:** cache idempotent SOAP responses; document TTLs.

### Risks

- **SOAP's tooling quirks.** Mitigation: use well-tested libraries
  (Spring WS, JAXB); avoid manual XML.
- **WSDL changes breaking the adapter.** Mitigation: contract tests
  catch drift; regenerate stubs on WSDL update.
- **Performance overhead.** Mitigation: benchmark; cache where
  possible.

### Enables

- Phase 7 (SOAP call metrics and dashboards).
- Real-world enterprise integration (the pattern is directly
  applicable to banking, insurance, government systems).

---

## Phase 7 — Hardening

> **Status:** Hardening incomplete
>
> **Depends on:** All previous phases

### Goal

Take the platform from "works" to "production-ready." Add the operational
practices, security hardening, and observability polish that separates a
demo from a system.

### Deliverables

- **Alertmanager:** routes Prometheus alerts to Slack, email, and
  PagerDuty (configurable).
- **Distributed tracing polish:** exemplars in Prometheus; full
  cross-service trace coverage.
- **Tail sampling** in the OTel Collector: keep errors + slow traces,
  sample the rest.
- **Postgres exporter:** full database metrics in Prometheus.
- **Redis exporter:** cache hit rates, memory usage.
- **Kafka exporter:** consumer lag, topic offsets.
- **Full dashboards:** per-service, per-database, per-cache.
- **Load testing:** k6 or Gatling scenarios for every external API.
- **Chaos testing:** Toxiproxy to simulate network failures.
- **Secret rotation:** automated via a runbook + scripts.
- **Runbooks:** complete set (deploy, restore, rotate, incident
  response, scaling).
- **Security review:** OWASP API Top 10 checklist against every
  endpoint.
- **Compliance documentation:** SOC 2-style control mapping.

### Exit Criteria

- [ ] Every alert fires in a test scenario.
- [ ] Alerts route to the correct channel (Slack for warnings,
  PagerDuty for criticals).
- [ ] Load test sustains expected throughput with < 1% error rate.
- [ ] Chaos test: killing a random service causes no user-visible
  errors (retries absorb the impact).
- [ ] Secrets rotate without downtime, verified end-to-end.
- [ ] All runbooks have been exercised at least once (dry-run mode).
- [ ] Security review finds no high-severity issues.
- [ ] All metrics, logs, and traces are correlated via trace IDs.

### Key Decisions to Make

- **Alertmanager routing:** which alerts go to which channel;
  silenced during maintenance.
- **Sampling policy:** tail sampling thresholds (keep all errors,
  10% of successes).
- **SLO definitions:** availability, latency, error rate; error
  budget policy.
- **Backup retention policy:** how far back; where stored.
- **Incident response:** who's on-call; escalation paths.

### Risks

- **Over-alerting.** Mitigation: alert only on user-visible symptoms,
  not causes; iterate after living with alerts for a week.
- **Alert fatigue.** Mitigation: weekly review; retire alerts that
  never fire.
- **Backup/restore never tested.** Mitigation: monthly restore drill
  as a scheduled task.
- **Secrets drift.** Mitigation: automated rotation; audit logs.

### Enables

- Ongoing operation.
- The platform is feature-complete.

---

## Cross-Cutting Concerns by Phase

Some concerns span multiple phases. This table shows when each one is
introduced and refined.

| Concern | Phase Introduced | Phase Refined |
|---------|-----------------|---------------|
| **Authentication** | 0 (Keycloak), 1 (JWT in services) | 3 (mTLS), 7 (rotation) |
| **Observability** | 0 (metrics, logs, traces infrastructure) | 1 (per-service), 7 (dashboards, sampling) |
| **Persistence** | 0 (Postgres schemas) | 1 (Flyway), 3 (Redis caching), 5 (Kafka durability) |
| **Testing** | 1 (unit, integration, contract) | 4 (load), 7 (chaos) |
| **Documentation** | 0 (READMEs), 1 (ADRs, runbooks) | 7 (complete runbook set) |
| **Security** | 0 (secrets, TLS), 1 (JWT authz) | 3 (mTLS), 5 (HMAC), 7 (review) |
| **Reliability** | 1 (retries, timeouts) | 3 (circuit breakers), 5 (DLQ), 7 (chaos) |

---

## Why This Order

The phase sequence isn't arbitrary. Here's the reasoning for the
non-obvious choices:

**REST before GraphQL.** GraphQL aggregates data from REST services.
Without REST services, there's nothing for GraphQL to query.

**REST before gRPC.** Internal gRPC calls replace some REST calls
between services. You need services with REST APIs first, then you can
opt into gRPC where it helps.

**gRPC before WebSocket.** Real-time notifications depend on stable
internal contracts (gRPC) and event sources (Kafka, from services). The
gRPC phase also introduces streaming patterns that inform the WebSocket
design.

**Webhooks before SOAP.** Webhooks introduce event-driven patterns
(producer/consumer, retry, DLQ) that the SOAP integration reuses. SOAP
is the more specialized case; it benefits from the patterns
established by webhooks.

**SOAP last among the protocols.** SOAP is an integration boundary, not
a core service protocol. It appears once the platform is stable,
representing the "legacy world we must integrate with."

**Hardening last.** You can't tune what doesn't exist. Hardening
requires all metrics, logs, and traces flowing. Doing it earlier would
mean re-tuning repeatedly.

---

## Phase Handoffs

Each phase leaves the repository in a **runnable state**. A new
contributor can clone at the end of any phase and have a working
system.

**Phase 0 → Phase 1:** Contributor runs `docker compose up`, sees a
fully-configured stack with no business services. They start Phase 1
knowing every dependency is ready.

**Phase 1 → Phase 2:** Contributor runs REST services, tests endpoints
via Postman or curl, sees them in Grafana. They start Phase 2 with
working APIs to aggregate.

**Phase 2 → Phase 3:** Contributor queries GraphQL and REST. gRPC is
introduced without breaking existing protocols — they coexist.

**Phase 3 → Phase 4:** Contributor sees services communicating via
gRPC. WebSocket is added on top, using gRPC internally.

**Phase 4 → Phase 5:** Contributor sees real-time updates. Webhooks
extend the notification pattern to external subscribers.

**Phase 5 → Phase 6:** Event-driven patterns are well-established.
SOAP integration reuses them.

**Phase 6 → Phase 7:** All protocols are in place. Hardening tunes the
whole system.

**Phase 7 → Operation:** The platform is feature-complete. Future work
is maintenance and incremental improvement.

---

## Timeline Estimate

Rough effort estimates, assuming a single experienced Java/Spring
developer working part-time:

| Phase | Estimated Effort | Notes |
|-------|------------------|-------|
| 0 | 2–3 weeks | Original scope estimate; deployment evidence required |
| 1 | 3–4 weeks | Three services with tests and dashboards |
| 2 | 2–3 weeks | GraphQL gateway with resolvers and DataLoader |
| 3 | 3–4 weeks | Two services, proto generation, mTLS |
| 4 | 2 weeks | Notification service, load testing |
| 5 | 2–3 weeks | Inbound and outbound webhooks with reliability |
| 6 | 2 weeks | SOAP adapter with contract tests |
| 7 | 3–4 weeks | Observability polish, chaos testing, runbooks |
| **Total** | **~20–25 weeks** | Part-time, single developer |

Effort scales roughly linearly with team size, up to a point. Beyond 3
developers, coordination overhead starts to matter — parallelize phases
where dependencies allow (e.g., Phase 2 and Phase 3 can partly overlap).

---

## What "Done" Looks Like

Release acceptance requires evidence that the platform:

- **Runs** every protocol in its proper context (REST for public APIs,
  GraphQL for flexible queries, gRPC internally, WebSocket for
  real-time, Webhook for events, SOAP at the enterprise boundary).
- **Observes** itself completely (metrics, logs, traces) with
  correlation between all three signals.
- **Recovers** from failures (circuit breakers, retries, DLQs,
  backup/restore).
- **Scales** horizontally (stateless services, shared cache,
  partitioned storage).
- **Documents** itself (READMEs, ADRs, runbooks, dashboards).
- **Protects** itself (TLS, secrets, HMAC signatures, mTLS).
- **Alerts** on real problems (SLO-based alerts, not noise).

It's a reference architecture — smaller than a real production system,
but demonstrating the patterns that a real system would use.

---

## Feedback

If a phase's scope is unclear, too broad, or missing something,
open an issue. Phases are plans, not contracts — they can change based
on what we learn implementing them.
