# ADR-0004: Shared Module Strategy

> **Status:** Accepted
> **Date:** 2025-01-15
> **Deciders:** Platform team
> **Supersedes:** —
> **Superseded by:** —

## Context

By the end of Phase 1, three business services (product, customer, order)
each contained near-identical copies of cross-cutting classes:

- **Domain value objects:** `Money`, `Email`, `PhoneNumber`.
- **Web utilities:** `PageResponse`, `ProblemDetails`,
  `ProblemDetailsFactory`, `PageableResolver`.
- **Configuration:** `CorsConfig`, `PaginationConfig`, `RequestIdFilter`.
- **Test infrastructure:** `AbstractIntegrationTest`, `TestSecurityConfig`.

Phase 1's retrospective identified this duplication as the top priority for
Phase 2. The problem was concrete:

- Any change to a shared class required three coordinated PRs.
- Small divergences were already appearing (e.g., a `PageResponse` that
  serialized nulls differently across services).
- Each new service would multiply the duplication.

## Decision

Extract shared code into **two Maven modules**:

- **`shared/domain`** — pure value objects with JPA and Bean Validation
  annotations. No Spring dependencies.
- **`shared/common-web`** — web-layer utilities exposed via Spring Boot
  **auto-configuration**. Services get the beans automatically when they
  add the dependency.

Each service's `pom.xml` declares its dependencies explicitly. Beans are
overridable via `@ConditionalOnMissingBean`.

Test infrastructure extraction is **deferred**. Each service keeps its own
`AbstractIntegrationTest` and `TestSecurityConfig` for now.

## Rationale

### Why extract at all?

Copy-paste has already been tried (Phase 1). It works for two or three
services; it breaks at five. Every change requires N coordinated PRs.
Divergences accumulate silently — a `ProblemDetails` class that handles
nulls differently in one service, a `PageableResolver` that throws a
different exception type in another.

A shared module makes the class exist once. Consistency becomes structural,
not enforced by code review.

### Why Maven modules, not a plugin or code generator?

- **Maven modules** are the standard for shared code in a multi-module
  repository.
- **A code generator** (e.g., copying from a template) adds tooling
  complexity for no benefit — the source of truth is still somewhere, and
  the generated copies still drift.
- **A shared git subtree** would work, but Maven modules are the idiomatic
  choice for a monorepo.

### Why two modules, not one?

The dependency profiles differ:

| Module | Depends on | Consumed by |
|--------|-----------|-------------|
| `shared/domain` | `jakarta.persistence-api`, `jakarta.validation-api` | Services with JPA entities |
| `shared/common-web` | Spring Web, Security, Data, Micrometer Tracing | Services with HTTP APIs |

Merging them would force every consumer to depend on everything. Two
modules let a consumer pick what it needs. In practice, all current
services depend on both, but the split documents intent.

### Why Spring Boot auto-configuration?

Alternative approaches:

- **`@Import(SharedWebConfig.class)` in each service.** Explicit but
  verbose. Every service would need the import, and forgetting it would
  fail at runtime with "no bean of type PageableResolver."
- **`@ComponentScan("com.example.omp.shared")`.** Fragile: requires the
  package to be included in every service's scan path. Breaks when a
  service narrows its scan path.
- **Auto-configuration** (chosen). Register a `@AutoConfiguration` class in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
  Spring Boot 3 discovers it automatically.

Auto-configuration is the modern Spring Boot 3 pattern for library beans.
It's how Spring Cloud, Micrometer, and other libraries expose beans.
Services get the shared beans with zero configuration.

### Why `@ConditionalOnMissingBean` on every bean?

So services can override. A service that needs a different `RequestIdFilter`
(a different header name, or a different MDC key) can declare its own bean
and the shared version steps aside.

This is essential for testing. A service whose tests need a different
`PageableResolver` behavior for a specific test can override it for that
test's configuration.

### Why keep JPA annotations in `shared/domain`?

`Money`, `Email`, and `PhoneNumber` are `@Embeddable` with `@Column`
annotations. Two alternatives were considered:

- **Strip JPA from shared/domain.** Each service defines its own
  `@Embeddable` wrapper delegating to a pure shared value object. Result:
  three wrapper classes per value object, reintroducing duplication.
- **Keep JPA in shared/domain.** The shared module depends on
  `jakarta.persistence-api` (as `provided` scope). Services already have
  JPA on their classpath via `spring-boot-starter-data-jpa`.

Chosen: keep JPA. The dependency is a compile-time API (no runtime
footprint), and the alternative reintroduces the duplication we're trying
to eliminate.

### Why not extract test infrastructure yet?

`AbstractIntegrationTest` and `TestSecurityConfig` differ across services:

- The integration test starts a Postgres container with the service's
  schema (`products`, `customers`, `orders`). A shared base class would
  need to be parameterized — the parameterization complexity rivals the
  duplication.
- The security config's mock tokens vary. Phase 1's services had a
  `customer_id` claim need in some tests; the gateway has different needs.

Extracting these now would trade one kind of complexity for another.
**Revisit at five or more services**, or when a specific bug appears in
the duplicated test infrastructure.

## Consequences

### Positive

- **Single source of truth.** `Money` is defined once. `PageResponse` is
  defined once. Any change is applied everywhere.
- **Consistency by construction.** Every service uses the same
  `ProblemDetails` shape, the same pagination defaults, the same CORS
  behavior. Not because a reviewer caught a divergence, but because the
  class is the same.
- **Smaller codebases.** Removing duplicated classes reduced the platform
  by ~1,070 lines (measured at the time of extraction).
- **Cheaper new services.** A new service adds two dependencies and gets
  cross-cutting concerns for free.
- **Explicit sharing.** A service's `pom.xml` shows exactly what's shared
  (via the `omp-shared-*` dependencies) versus what's local.
- **Testability.** Shared classes are unit-tested once. The
  `MoneyTest` in `shared/domain` runs for every service.

### Negative

- **Build coupling.** Every service depends on the shared modules at
  version `1.0.0-SNAPSHOT`. A breaking change in a shared module requires
  coordinated updates. In practice, this is manageable in a single
  repository with a single team.
- **Framework creep.** `shared/domain` now depends on `jakarta.persistence-api`.
  A "pure" domain module would not. We accepted this trade-off for the
  duplication savings.
- **Learning curve.** New contributors must understand that `PageResponse`
  comes from `shared/web`, not from each service's `dto` package. The
  shared module's README documents this.
- **Auto-configuration invisibility.** Services don't see the
  `SharedWebAutoConfiguration` class unless they look. This is by design
  (it's how Spring Boot auto-config works), but it means a service's
  behavior isn't fully visible from the service's own code.

### Neutral

- **Versioning.** The shared modules use the platform version
  (`1.0.0-SNAPSHOT`) rather than independent versions. When the platform
  releases, everything releases together. Independent versioning can be
  introduced later if needed.
- **Not a library.** The shared modules are internal to this platform,
  not published to a repository. They're consumed only by services in the
  same reactor.

## Implementation

- `shared/domain` — Maven module, JPA-annotated value objects.
- `shared/common-web` — Maven module, Spring Boot auto-configuration.
- Each service's `pom.xml` declares the dependencies.
- `shared/README.md` documents the strategy and migration procedure.
- Batch 5.2 migrated the existing three services: 25 files deleted, 30+
  files with updated imports, no behavior change.

## Related Decisions

- **ADR-0001:** Schema-per-service in a single Postgres instance.
- **ADR-0002:** Promtail for logs.
- **ADR-0003:** Dev + prod Compose overlay pattern.
- [ADR-0005](0005-grpc-for-internal-services.md): internal gRPC.

## References

- Spring Boot auto-configuration:
  https://docs.spring.io/spring-boot/docs/current/reference/html/using.html#using.auto-configuration
- `spring-boot-configuration-processor` (for IDE metadata):
  https://docs.spring.io/spring-boot/docs/current/reference/html/configuration-metadata.html