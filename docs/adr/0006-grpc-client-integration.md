# ADR-0006: gRPC Client Integration in order-service

> **Status:** Accepted
> **Date:** 2025-01-15
> **Deciders:** Platform team
> **Supersedes:** —
> **Superseded by:** —

## Context

In Batches 7.2 and 7.3, inventory-service and payment-service were built as
gRPC-only services. Both are called exclusively by order-service. That means
order-service needs gRPC clients for them.

Order-service already calls product-service and customer-service via REST
(using Spring Cloud OpenFeign — see Phase 1). With Phase 3, order-service
speaks two protocols:

- **REST** to product-service and customer-service.
- **gRPC** to inventory-service and payment-service.

This ADR documents the client-side decisions for gRPC.

## Decision

Order-service uses **`grpc-client-spring-boot-starter`** to inject gRPC
stubs. Each downstream service has:

- A **client wrapper class** (`InventoryClient`, `PaymentClient`) that
  hides proto types behind domain DTOs.
- **Global client interceptors** for JWT forwarding and request-ID
  propagation (registered via `GlobalClientInterceptorRegistry`).
- **gRPC deadlines** at the channel level (3s for inventory, 5s for payment).
- **Resilience4j circuit breakers and retries** on every method.
- **`GrpcExceptionMapper`** translating gRPC statuses to the order-service's
  domain exceptions.

Cross-service calls use the **token relay** pattern (same as Phase 1's
Feign clients): the caller's JWT is forwarded, not a service account token.

Cross-service consistency is achieved via **compensating transactions**,
not distributed transactions. If a downstream call fails mid-flow, the
already-committed calls are compensated (released, cancelled) on a
best-effort basis.

## Rationale

### Why the client-side starter?

- **`@GrpcClient` annotation** — the standard way to inject a stub in
  Spring Boot. Handles channel creation, lifecycle, and disposal.
- **Interceptor registry** — a global registry means we register auth and
  request-ID interceptors once, and every stub uses them automatically.
- **Micrometer integration** — per-method client metrics (`grpc_client_*`)
  appear on the Prometheus endpoint without additional wiring.
- **Load balancer integration** — with `grpc-client-spring-boot-starter`
  we can later switch from `static://inventory-service:9091` to
  `discovery:///inventory-service` for client-side load balancing across
  replicas (Phase 7).

### Why blocking stubs?

- The order-service code is inherently synchronous: a user hits
  `/api/v1/orders`, we call inventory, then payment, then return. Blocking
  stubs map cleanly to that flow.
- The async stub (returning `ListenableFuture`) adds complexity without
  benefit for single-call sequences.
- Streaming RPCs (used only by inventory's and payment's event streams)
  would use the async stub, but order-service doesn't consume those in
  Phase 3.

### Why wrappers instead of raw stubs?

- **Business logic shouldn't see proto types.** Proto-generated classes
  have builders, `getDefaultInstance()`, and other concerns that don't
  belong in the domain.
- **Error handling is centralized.** Every wrapper method catches
  `StatusRuntimeException` and delegates to `GrpcExceptionMapper`. If a
  new status code needs special handling, it changes in one place.
- **Testing is simpler.** The wrapper's public API uses domain types;
  tests mock the client without touching protobuf.

### Why token relay?

Same reasoning as ADR-0004's decision for Feign clients. Downstream
services apply their own per-user authorization. A service account would
lose that. Token relay preserves the user identity through the whole call
chain.

### Why compensation instead of transactions?

Distributed transactions (2PC) across heterogeneous protocols (gRPC + REST
+ SQL) aren't practical. The saga pattern — compensating transactions —
is the standard alternative:

1. Order-service calls inventory.reserve() for each line.
2. Order-service calls payment.createIntent().
3. Order-service saves the order.
4. If any step fails, the already-committed steps are compensated.

Compensation is best-effort. Failures in compensation are logged and
recovered later by background jobs (Phase 7). This gives eventual
consistency without distributed locks.

**Note:** there is a small window where a reservation exists but no order
does. This is intentional — the reservation expiration job (Phase 7)
cleans these up. Attempting to guarantee "no orphan reservations ever"
would require 2PC or an outbox pattern, both of which are Phase 5+.

## Consequences

### Positive

- **Consistent error handling.** All gRPC client errors flow through one
  mapper. Adding a new status code mapping is one change.
- **Business logic stays protocol-agnostic.** The service layer sees
  `ReservationDto`, not `Reservation` (proto).
- **Metrics and tracing are free.** The starter and Micrometer handle them.
- **Resilience is applied uniformly.** Circuit breakers and retries are
  annotations on wrapper methods, not scattered try/catch blocks.
- **Two protocols coexist cleanly.** REST (Feign) for product/customer,
  gRPC for inventory/payment. Each has its own conventions but both use
  the same domain-exception hierarchy.

### Negative

- **Two client libraries.** Feign for REST, grpc-spring-boot-starter for
  gRPC. Contributors must learn both. Mitigated by consistent patterns
  (wrappers, exception mappers, interceptors).
- **Compensation complexity.** Every operation that touches multiple
  downstreams must consider what to do on partial failure. This is
  inherent to microservices, but it's more code than a single-service
  transaction.
- **Orphan risk.** A best-effort compensation can leave a reservation or
  payment intent that isn't linked to an order. Cleanup is deferred to
  Phase 7's background jobs.
- **Deadlines are a new concept.** REST clients use HTTP timeouts (simpler).
  gRPC deadlines are propagated end-to-end; a client-side deadline
  cancels downstream work even if the downstream is mid-operation.
  Contributors must understand that deadlines are not just timeouts.

### Neutral

- **Protocol choice is per-call.** Adding a new downstream means deciding
  REST vs. gRPC. The rule: internal-only services with high call frequency
  or streaming needs → gRPC; external or infrequent → REST.
- **Not a full CQRS/event-sourcing model.** We use compensation, not
  events, in Phase 3. Phase 5 will introduce an outbox for order events;
  that's the point where "eventual consistency" becomes first-class.

## Implementation

- Client wrappers: `order-service/src/main/java/.../clients/grpc/`.
- Exception mapper: same package.
- Interceptors: same package, subpackage `interceptor`.
- Configuration: `GrpcClientConfig` registers interceptors globally.
- `application.yml` declares gRPC client addresses and deadlines.
- Tests use in-process gRPC servers with fake service implementations.

## Related Decisions

- **ADR-0001:** Schema-per-service.
- **ADR-0002:** Promtail for logs.
- **ADR-0003:** Dev + prod Compose overlay pattern.
- **ADR-0004:** Shared module strategy.
- **ADR-0005:** gRPC for internal service communication.
- [ADR-0007](0007-mtls-between-services.md): mTLS between services; rotation
  acceptance remains open.

## References

- grpc-spring-boot-starter:
  https://yidongnan.github.io/grpc-spring-boot-starter/
- gRPC deadlines: https://grpc.io/docs/guides/deadlines/
- Saga pattern: https://microservices.io/patterns/data/saga.html
- In-process servers for testing:
  https://grpc.io/docs/guides/testing/
