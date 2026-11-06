
---

## File 6: `docs/adr/0005-grpc-for-internal-services.md`

```markdown
# ADR-0005: gRPC for Internal Service Communication

> **Status:** Accepted
> **Date:** 2025-01-15
> **Deciders:** Platform team
> **Supersedes:** —
> **Superseded by:** —

## Context

Phases 1 and 2 established a uniform communication pattern: **all
service-to-service calls go over REST/HTTP with JSON payloads and
JWT-based authentication.**

By the start of Phase 3, the platform has three REST services (product,
customer, order) plus a GraphQL gateway. Phase 3 adds two more services
(`inventory-service`, `payment-service`), and both are called exclusively
by other services — they have no external clients.

Continuing the REST-for-everything pattern would work. But it has costs:

- **Serialization overhead.** JSON is verbose; parsing costs CPU.
- **Weak typing.** REST endpoint contracts are enforced by OpenAPI + review,
  not by the compiler.
- **Latency.** HTTP/1.1 (REST/1) has head-of-line blocking; gRPC over
  HTTP/2 multiplexes streams.
- **Boilerplate.** Every REST client needs request/response DTOs and error
  handling.
- **No streaming.** REST is request/response only. Some operations
  (real-time stock updates, event streams) fit naturally into
  server-streaming RPCs.

## Decision

Adopt gRPC for **internal, service-to-service communication where it earns
its keep.** Specifically:

- **New internal services (`inventory-service`, `payment-service`) expose
  gRPC only.** No REST endpoints.
- **`order-service` calls them via gRPC clients.**
- **Existing REST services (product, customer) stay REST.** Their callers
  already use REST; converting them would add churn with no benefit.
- **External interfaces (Traefik, GraphQL gateway, public APIs) stay
  REST/GraphQL.** gRPC is not exposed externally.

This is a **surgical adoption**: gRPC where it provides measurable benefit,
REST where it's already working.

## Rationale

### Why not adopt gRPC everywhere?

Two alternatives were considered:

**A. Convert all internal calls to gRPC.**
- Would require rewriting `order-service` → `product-service` and
  `order-service` → `customer-service` clients.
- Neither is a hot path. Their REST interfaces work.
- Churn without benefit.

**B. Keep everything REST.**
- Would miss the specific benefits gRPC provides for the new services:
  - `inventory-service` has streaming operations (stock updates).
  - `payment-service` benefits from strong typing (amounts in Money
    messages, provider references).
  - Both are called frequently from `order-service`; the latency win
    matters.

The chosen surgical approach captures the benefits where they exist and
avoids the churn where they don't. It's also how real enterprises adopt
gRPC: incrementally, for the use cases that justify it.

### Why gRPC over REST for the new services?

- **Streaming.** gRPC supports server-side, client-side, and bidirectional
  streaming natively. REST requires SSE, WebSocket, or chunked HTTP — all
  of which are add-ons with rough edges.
- **Strong typing.** `.proto` files are the contract. The `protoc` compiler
  generates Java classes; a mismatch between client and server is a
  compile-time error, not a runtime 400.
- **Performance.** HTTP/2 multiplexing, binary serialization, smaller
  payloads. For high-frequency calls (availability checks on every order),
  the difference is measurable.
- **Code generation.** Client stubs are generated from the `.proto` — no
  hand-written DTOs, no manual Jackson configuration.
- **Deadlines and cancellation.** gRPC has built-in deadline propagation.
  If the client sets a 200ms deadline, downstream calls inherit it, and
  slow services get cancelled rather than piling up.

### Why not GraphQL for internal services?

GraphQL is a **client-facing** technology. It solves "different clients
need different data shapes." Internal services have well-defined contracts
(one shape per call), so GraphQL's flexibility is unused.

gRPC's strong typing is better suited to internal APIs.

### Why HTTP/2 (and not a custom protocol)?

gRPC **is** HTTP/2. It's not a new transport — it's a specific use of
HTTP/2's framing:

- HTTP/2 handles multiplexing, flow control, header compression.
- gRPC adds the framing (length-prefixed protobuf messages), status codes,
  and the four streaming patterns.

Because it's HTTP/2, gRPC coexists with existing infrastructure: load
balancers, service meshes, and — with care — even Traefik. It also runs on
the same networks (our `app` and `data` networks).

## Consequences

### Positive

- **Strong contracts.** The `.proto` files are the source of truth. Clients
  and servers are generated from them.
- **Streaming where needed.** Inventory updates and payment events stream
  natively.
- **Lower latency.** Binary serialization and HTTP/2 multiplexing.
- **Type safety.** Message fields, enums, and service methods are
  compile-checked.
- **Protocol-appropriate.** Internal services use a protocol designed for
  internal service calls. External APIs use REST/GraphQL where they belong.

### Negative

- **Two protocols in the codebase.** Contributors must understand when to
  use REST vs. gRPC. Enforced by convention and code review, not by tooling.
- **Debugging is harder.** `curl` works against REST, not gRPC. Requires
  `grpcurl` or similar tools.
- **Tooling.** IDEs need protobuf plugins (most modern IDEs ship with them,
  but the setup varies).
- **Code generation in the build.** The `shared/proto` module adds a build
  step. It's fast, but it's a step.
- **Backpressure and streaming complexity.** Server-streaming RPCs need
  careful lifecycle management (when to close, how to reconnect, how to
  handle backpressure). Phase 3 introduces basic patterns; Phase 7
  hardens.

### Neutral

- **Network topology unchanged.** gRPC runs over the same Docker networks.
  No new networks, no new ingress rules (gRPC is internal-only).
- **Observability extends naturally.** Micrometer's gRPC instrumentation
  provides metrics; the OTel collector forwards spans. Same backends.

## Implementation

- Proto definitions: `shared/proto/src/main/proto/`.
- Code generation: `protobuf-maven-plugin` in `shared/proto/pom.xml`.
- Services implementing gRPC: `inventory-service`, `payment-service`
  (Phase 3).
- gRPC clients: `order-service` (Phase 3 Batch 7.4).
- mTLS: Phase 3 Batch 7.5.

## Related Decisions

- **ADR-0001:** Schema-per-service in a single Postgres instance.
- **ADR-0002:** Promtail for logs.
- **ADR-0003:** Dev + prod Compose overlay pattern.
- **ADR-0004:** Shared module strategy.

## References

- Protocol Buffers: https://protobuf.dev/
- gRPC Java: https://grpc.io/docs/languages/java/
- gRPC status codes: https://grpc.io/docs/guides/status-codes/
- gRPC streaming: https://grpc.io/docs/what-is-grpc/core-concepts/#streaming-rpcs
- `protobuf-maven-plugin`:
  https://www.xolstice.org/protobuf-maven-plugin/