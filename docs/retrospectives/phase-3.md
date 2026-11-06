# Phase 3 Retrospective — gRPC

> Current status: [the acceptance backlog](../../TASKS.md) and
> [implementation evidence](../task-implementation-report.md) supersede
> the original completion claims in this historical record.

> **Status:** Historical implementation record
> **Period:** Phase 3
> **Completed:** 2025-01-15

## What we built

Two new gRPC-only services, a proto module, client integration in
order-service, and mTLS between internal services.

### Services

| Service | Type | RPCs | LOC |
|---------|------|------|-----|
| **inventory-service** | gRPC-only | 7 (unary + client streaming + server streaming) | ~2,200 |
| **payment-service** | gRPC-only | 6 (unary + server streaming) | ~2,600 |

### Cross-cutting

- **`shared/proto`** module with 3 `.proto` files and code generation.
- **Order-service gRPC clients** with token relay, trace propagation,
  deadlines, circuit breakers, and error mapping.
- **mTLS** between all internal gRPC communication, with a local CA and
  per-service certificates.
- **Compensation saga** for order creation, cancellation, and shipment.

### Documentation

- **ADR-0005:** gRPC for internal services.
- **ADR-0006:** gRPC client integration.
- **ADR-0007:** mTLS.
- **ADR-0008:** Compensation strategy.
- **This retrospective.**

## Metrics

| Metric | Count |
|--------|-------|
| **Batches** | 6 (7.1, 7.2, 7.3, 7.4, 7.5, 7.6) |
| **Proto files** | 3 |
| **New services** | 2 |
| **New gRPC RPCs** | 13 |
| **New ADRs** | 4 |
| **Test files added** | ~25 |
| **Total files added (Phase 3)** | ~155 |
| **Lines of Java (new services)** | ~4,800 |
| **Lines of proto (SDL)** | ~400 |
| **gRPC patterns exercised** | 3 of 4 (unary, client streaming, server streaming) |

## What worked well

### 1. Proto-first design

Writing the `.proto` files before any service implementation was the right
call. The proto is the contract; the generated Java classes are the
guaranteed interface. Adding a service method starts with the `.proto`
change, which forces explicit consideration of the contract before any
implementation.

Generated code made the services less error-prone: no hand-written DTOs,
no Jackson configuration, no mismatch between client and server.

### 2. The gRPC server patterns

`grpc-spring-boot-starter` and the standard gRPC Java patterns made
implementation straightforward:

- `@GrpcService` for discovery.
- `StreamObserver` for RPC responses.
- `ServerCallStreamObserver` for streaming lifecycle.
- `@GrpcAdvice` for exception mapping.

Each pattern is clean and well-documented. Engineers familiar with Spring
MVC will recognize the parallels (`@GrpcAdvice` ≈ `@RestControllerAdvice`,
`@GrpcService` ≈ `@RestController`).

### 3. In-process gRPC testing

`InProcessServerBuilder` makes client tests fast and deterministic. No
Docker, no network, no port allocation. A fake service implementation
substitutes for the real service; the client code is tested in full.

This is the standard pattern for gRPC testing, and it worked well.

### 4. Idempotency keys

The decision to make every state-changing RPC idempotent (via natural
keys or explicit idempotency keys) has paid off:

- `ReserveStock` is idempotent on `(orderId, sku)`.
- `CreatePaymentIntent` and `RefundPayment` are idempotent on
  `idempotencyKey`.
- Network retries are safe. Order-service can retry without fear.

The combination of client-side retries (Resilience4j) and server-side
idempotency means transient network failures don't create duplicates.

### 5. mTLS on internal gRPC

Adding mTLS was less disruptive than expected. The certificate tooling
is simple (three `openssl` commands to generate a CA and sign
certificates). The gRPC config is a few lines per service. Test bypass
via `PLAINTEXT` keeps tests fast.

The result is a real security improvement: internal service calls are now
authenticated on both ends, not just by user JWT.

## What was hard

### 1. Streaming lifecycle

Server-streaming RPCs require careful management of:
- **Backpressure.** The `ServerCallStreamObserver.isReady()` check is
  essential. Skipping updates when the client is slow is acceptable for
  stock/payment events (they're idempotent snapshots).
- **Cancellation.** `setOnCancelHandler` is required to clean up the
  subscription registry. Without it, dead subscriptions leak.
- **Multi-replica fan-out.** The in-memory subscription registry doesn't
  work across replicas. Phase 7 will need Redis pub/sub for this.

We documented the multi-replica limitation clearly, and Phase 3's scope
accepts it. But it's a real design constraint that will bite when the
platform scales.

### 2. Compensation logic

The saga pattern is conceptually simple but operationally subtle:

- **Where to place the try/catch?** The compensation logic must run
  outside the transactional boundary, but the failure can come from
  inside it. We structured `OrderService.create` as a non-transactional
  orchestrator with a small transactional `tx.create`.
- **What order to compensate in?** Reverse order. Documented in ADR-0008.
- **What if compensation fails?** Log and continue. Phase 7's cleanup
  job reconciles orphans.

The result is best-effort consistency, not guaranteed. This is a
well-known property of sagas, but it's a departure from the ACID that
developers used to monoliths expect.

### 3. mTLS file ownership in production

The certificate files must be readable by the container's `app` user
(uid 1000). In dev, this works because the developer's files are already
owned by uid 1000. In prod, the files are generated by `sudo` (owned by
root), and the container can't read them.

**Solution:** after generation, `sudo chown -R 1000:1000 /etc/omp/tls/<service>/`.
Documented in the runbook, but easy to forget.

**Phase 7 fix:** an entrypoint or init container that copies certificates
to a tmpfs with correct permissions. Or Docker secrets (different
mechanism, similar complexity).

### 4. gRPC tooling for debugging

`curl` works for REST. For gRPC, we need `grpcurl` (or similar). This is
a real friction point during development:
- `grpcurl` must be installed separately.
- Invocations are more verbose (`grpcurl -plaintext -H "..." -d '{...}' host:port service/method`).
- Streaming responses require careful handling.

**Mitigations:**
- gRPC reflection is enabled in dev, so `grpcurl list` and `describe`
  work.
- README examples show the common patterns.
- IDE plugins (IntelliJ has gRPC support) provide a GUI.

Still, the tooling gap vs. REST is real.

### 5. Event publication timing

Publishing a payment event **inside** the transaction means subscribers
see events for state that might not commit. We solved this with
`TransactionSynchronization.afterCommit()` — publish only after the
transaction commits. But this pattern isn't obvious and requires careful
application.

**Lesson:** event publishing and transactional boundaries are a
recurring source of subtle bugs. Phase 5's outbox pattern will formalize
this.

## What we'd do differently

### 1. Batch endpoints from the start (again)

Same lesson as Phase 2. The DataLoader batching in the GraphQL gateway
was limited because product-service and customer-service didn't have
batch endpoints. In Phase 3, the same problem appeared with inventory
reservations — a bulk reserve exists (client streaming), but only for
one order. Cross-order batch operations aren't supported.

**Phase 7 might add** `POST /api/v1/products/batch` and similar endpoints
to reduce N+1 patterns in the aggregate layer.

### 2. Redis pub/sub for streaming

The in-memory subscription registry is a known limitation. It should have
been Redis from the start, using the existing Redis infrastructure.

**Phase 7 fix:** move `StockUpdateStreamHandler` and
`PaymentEventStreamHandler` subscriptions to Redis pub/sub. The change
is localized to the handlers.

### 3. Extract the domain module

`Money`, `Email`, `PhoneNumber`, and the saga pattern helpers
(`TransactionSynchronization.afterCommit` wrapper) are ready for
extraction to `shared/domain`. `Money` is already there; the rest
should follow.

**Phase 4 task.**

### 4. Standardize on a saga helper

The try/catch-compensate pattern appears in three places
(`OrderService.create`, `OrderService.cancel`, `OrderService.ship`). A
small helper class would DRY this up:

```java
Saga.run()
    .step(() -> inventoryClient.reserve(...), r -> inventoryClient.release(r.id()))
    .step(() -> paymentClient.createIntent(...), p -> paymentClient.cancel(p.id()))
    .execute();