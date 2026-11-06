# Phase 2 Retrospective — GraphQL Gateway

> Current status: [the acceptance backlog](../../TASKS.md) and
> [implementation evidence](../task-implementation-report.md) supersede
> the original completion claims in this historical record.

> **Status:** Historical implementation record
> **Period:** Phase 2
> **Completed:** 2025-01-15

## What we built

Two major deliverables:

### 1. Shared Modules (Batches 5.1–5.2)

Extracted cross-cutting code from Phase 1's three services into two Maven
modules:

- **`shared/domain`** — `Money`, `Email`, `PhoneNumber`.
- **`shared/common-web`** — `PageResponse`, `ProblemDetails`,
  `ProblemDetailsFactory`, `PageableResolver`, `RequestIdFilter`,
  `CorsConfig` (via `CorsProperties`), pagination configuration.

Services migrated from local copies to shared modules. ~1,070 lines of
duplicated code removed. No behavior change — all existing tests passed
unchanged.

### 2. GraphQL Gateway (Batches 6.1–6.4)

A new service that exposes a single GraphQL endpoint aggregating the three
REST services:

- Schema-first design (`schema.graphqls`, ~300 lines).
- Custom scalars (`Date`, `DateTime`, `Decimal`).
- Queries, mutations, and cross-service fields.
- Feign clients for the three downstream services.
- DataLoader for batching cross-service lookups.
- Error translation from HTTP statuses to GraphQL error codes.
- Full test suite: resolver unit tests, integration tests, schema
  validation, DataLoader behavior tests.

## Metrics

| Metric | Count |
|--------|-------|
| **Batches** | 6 (5.1, 5.2, 6.1, 6.2, 6.3, 6.4) |
| **Shared module files** | 18 |
| **GraphQL gateway files** | ~95 |
| **Total files added (Phase 2)** | ~113 |
| **Files deleted (Phase 2)** | ~25 |
| **Net new files** | ~88 |
| **Java files added** | ~65 |
| **Tests added** | ~35 |
| **ADRs written** | 1 (ADR-0004) |
| **Lines of Java (gateway)** | ~4,500 |
| **Lines of schema (SDL)** | ~300 |

## What worked well

### 1. Shared module extraction paid off immediately

The retrospective from Phase 1 flagged the duplication. Extracting it
into two Maven modules took one batch of creation + one batch of
migration. The effort was ~20 files for the modules and ~30 file edits
across services. The benefit was immediate:

- No more "which `PageResponse` handles nulls this way?" questions.
- Adding the gateway didn't require copying `PageResponse` a fourth time.
- Future services (Phase 3+) will get the cross-cutting concerns for free.

The Maven module approach was correct. Auto-configuration made the
consumption trivially easy — services add a dependency and get beans.

### 2. Schema-first GraphQL

Writing `schema.graphqls` before writing resolvers was the right call:

- The schema is reviewable as a document. Anyone can open it and see the
  API surface.
- Spring GraphQL's `@QueryMapping`/`@SchemaMapping` annotations map cleanly
  to schema fields.
- The schema validation test (`SchemaTest`) enforces that every field has
  a resolver. Adding a field to the schema and forgetting the resolver
  fails at build time, not in production.
- Tooling works: GraphiQL reads the schema and offers autocomplete.

### 3. Client DTOs vs GraphQL types separation

We defined two parallel type systems:

- **Client DTOs** (`ProductClientDto`, `CustomerClientDto`, etc.) mirror
  the downstream REST responses.
- **GraphQL types** (`ProductGql`, `CustomerGql`, etc.) are the Java
  objects returned by resolvers.

The separation is worth the extra classes:

- Client DTOs can be as loose as the downstream services allow (with
  `@JsonIgnoreProperties(ignoreUnknown = true)`).
- GraphQL types are strict — they mirror the schema's nullability.
- Cross-service fields (e.g., `Order.customer`) are handled by
  `@SchemaMapping` methods that take a `OrderGql` source and return a
  resolved `CustomerGql`.
- The mappers (`ProductMapper.toGql(dto)`) make the transformation
  explicit and testable.

This is more code than reusing DTOs directly, but the boundary is clean.

### 4. DataLoader deduplication even without batch endpoints

The Phase 1 REST services don't expose batch endpoints
(`POST /products/batch`). We expected this to make DataLoader useless
until Phase 3.

Actually, DataLoader still helped:

- **Deduplication within a tick** — a query with 3 order lines referencing
  the same product makes 1 call, not 3.
- **Parallelism** — distinct IDs are fetched in parallel, not sequentially.

The improvement is meaningful. And when Phase 3 adds batch endpoints, the
same DataLoader configuration will reduce N unique-ID calls to 1 batched
call without any code change.

### 5. Token relay for downstream calls

The gateway forwards the caller's JWT to downstream services via a Feign
interceptor. This is the right pattern:

- Downstream services see the actual user and apply their own authorization.
- The gateway doesn't need its own credentials.
- Logs and traces show the actual user's actions.

Alternatives considered (service account, gateway-issued tokens) would
have broken per-user authorization in downstream services.

## What was hard

### 1. GraphQL error semantics learning curve

GraphQL always returns HTTP 200 — even for business errors. The error
information is in the response body's `errors` array.

This is different from REST, and it's easy to get wrong:

- Test assertions that expect 404 for "not found" need to instead check
  the `errors` array for `code = NOT_FOUND`.
- Client code that switches on HTTP status needs to be rewritten to switch
  on the error code in the response body.
- Error monitoring tools (which alert on 5xx HTTP codes) don't catch
  GraphQL errors by default. Metrics need to be error-aware.

We solved this by:
- Documenting the error model in the schema file itself.
- Standardizing error codes (`NOT_FOUND`, `CONFLICT`, `VALIDATION_FAILED`,
  `FORBIDDEN`, `UNAUTHENTICATED`, `DOWNSTREAM_SERVICE_ERROR`).
- A custom `DataFetcherExceptionResolver` that maps exceptions to codes.
- A dashboard panel that tracks GraphQL error rate separately from HTTP
  error rate.

### 2. DataLoader without a batch endpoint

DataLoader's `BatchLoader` normally works when the downstream has a
batched API: "give me all products with these IDs in one request."

Without a batch endpoint, we had to make individual HTTP calls in the
`BatchLoader`. This works but:

- Doesn't reduce to 1 call. Reduces N calls to `unique-ids` calls.
- Requires parallel HTTP calls to hide latency.
- The parallelism is achieved via `parallelStream()` inside the loader
  — meaning the thread pool matters. If DataLoader's `BatchLoader` runs on
  a single-threaded executor, the parallelism is lost.

We mitigated with:
- `parallelStream()` for the individual fetches.
- Documenting the limitation in the DataLoader's Javadoc and this
  retrospective.
- Adding a Phase 3 task: batch endpoints on product-service and
  customer-service.

### 3. Testing GraphQL — two different approaches

Spring GraphQL provides two test tools:

- **`GraphQlTester`** — schema-aware, uses Spring GraphQL's client
  infrastructure, executes queries in-process.
- **REST Assured** — client-perspective, hits the real HTTP endpoint.

Neither alone is sufficient:

- `GraphQlTester` doesn't exercise the security filter chain or HTTP
  content negotiation. It's great for testing resolver behavior.
- REST Assured doesn't know the schema. It's great for testing the wire
  format but requires hand-written JSON for each test.

We used both:
- `@GraphQlTest` + `GraphQlTester` for resolver unit tests.
- `@SpringBootTest` + REST Assured for `GraphQlEndpointIT`.

This is more test files than a single approach, but the coverage is
complete.

### 4. ArchUnit rules for the gateway

The gateway's layering is different from the REST services:
- No repositories, no entities (no persistence).
- Clients, mappers, resolvers, GraphQL types form a different layering.

The `LayeringTest` rules needed adjusting:
- "graphql.type records are pure" (no Spring, no client dependencies).
- "mappers don't call clients."
- "clients don't depend on resolvers."

These are analogous to Phase 1's rules but adapted. The rule about
DataLoader (that cross-service field resolvers must use it) is not
expressible in ArchUnit — it's enforced by code review.

### 5. Feign error decoder vs GraphQL error mapper

Two layers of error translation:

1. **Feign error decoder** — translates downstream HTTP errors into Java
   exceptions (`ClientNotFoundException`, `ClientConflictException`, etc.).
2. **GraphQL exception resolver** — translates those exceptions into
   GraphQL errors with `extensions.code`.

The two-layer approach is clean but requires coordination:
- The exception type names must match the intent ("Client" not "Product"
  to stay service-agnostic).
- The GraphQL codes are the API contract — changing them is a breaking
  change.

We documented the mapping explicitly in the schema's header comment so
clients know what codes to expect.

## What we'd do differently

### 1. Batch endpoints from the start

If product-service and customer-service had exposed
`POST /api/v1/products/batch` from Phase 1, DataLoader would have been
trivially correct. We discovered the need during Phase 2.

**Phase 3 will add these.** They're a small addition to each service
(~2 files each), and they unlock real DataLoader batching.

### 2. Consider an interface for GraphQL types

Currently, GraphQL types (`ProductGql`, etc.) are records. If the schema
grows, adding a common interface for shared fields (e.g., every type has
`id`) might reduce boilerplate.

**Deferred.** At 5–6 types, records are fine. Revisit if the schema
doubles.

### 3. Add error-path tests earlier

Initial resolver tests focused on happy paths. Adding error paths was
retroactive. For Phase 3's gRPC services, we'll write error-path tests
alongside happy paths from the first batch.

### 4. Extract test infrastructure (still)

The Phase 1 retrospective deferred this. Phase 2 didn't change the
calculus much — with 4 services now, the duplication is noticeable but
still manageable.

**Revisit at 5+ services.** Phase 3 adds two gRPC services (inventory,
payment), bringing the total to 6. Extraction should happen in Phase 3
or early Phase 4.

## Deferred to later phases

| Item | Target Phase |
|------|--------------|
| Batch endpoints (`POST /products/batch`, `POST /customers/batch`) | Phase 3 |
| Shared test infrastructure (`shared/test`) | Phase 3 or 4 |
| GraphQL subscriptions (real-time push) | Phase 4 |
| GraphQL query complexity limits | Phase 3 (before production) |
| GraphQL persisted queries | Future (if mobile clients need them) |
| DataLoader thread pool tuning | Phase 7 |
| GraphQL federation (if multi-team) | Future / never |

## Conclusion

Phase 2 delivered:

1. **Shared modules** — extracted cross-cutting code, eliminating ~1,070
   lines of duplication. Services now consume shared beans via Spring
   Boot auto-configuration with zero configuration.

2. **GraphQL gateway** — a new service that exposes the platform's data
   through a flexible schema. Cross-service fields resolve by calling
   downstream services with token relay and trace propagation. DataLoader
   batches repeated lookups.

The **primary learning** is that shared modules are worth their weight
even at three services. The initial extraction took two batches; the
benefit compounds with every new service.

The **primary risk** is GraphQL's error semantics being different from
REST. Clients must check the `errors` array, not HTTP status codes. This
is documented and enforced by tests, but it requires client-side
awareness.

**Phase 2 is complete.**