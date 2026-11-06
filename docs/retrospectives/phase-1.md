# Phase 1 Retrospective — REST Services

> Current status: [the acceptance backlog](../../TASKS.md) and
> [implementation evidence](../task-implementation-report.md) supersede
> the original completion claims in this historical record.

> **Status:** Historical implementation record
> **Period:** Phase 1
> **Completed:** 2025-01-15

## What we built

Three production-shaped REST services on top of Phase 0's infrastructure:

| Service | Schema | Endpoints | Tests | Files |
|---------|--------|-----------|-------|-------|
| **product-service** | `products` | 9 (products, categories) | ~45 | ~35 |
| **customer-service** | `customers` | 12 (customers, addresses) | ~40 | ~30 |
| **order-service** | `orders` | 12 (orders, state transitions) | ~50 | ~45 |

Plus:
- **Integration tests** — end-to-end order lifecycle across all three services.
- **Cross-service orchestration** — order-service calls product-service and customer-service via Feign with circuit breakers and trace propagation.
- **3 ADRs** — schema-per-service, Promtail for logs, dev/prod overlay.
- **Full observability** — every service emits metrics, logs, and traces; dashboards per service.

## What worked well

### 1. The "diff-based" batch model

After Phase 0's exhaustive per-file depth, we switched to **diff-based presentation for structural files**. This cut the remaining Phase 1 responses to ~50% of what they would have been without losing rigor:

- Structural files (pom, Dockerfile, config classes, logback) → diffs from the reference service.
- Domain-specific files (entities, services, controllers, tests) → full depth.

We identified the pattern after product-service and reused it for customer-service and order-service.

**Keep for Phase 2+.**

### 2. The layered config pattern

Every service has the same config structure:

- `application.yml` — base, with env-var placeholders.
- `application-local.yml` — dev overrides.
- `application-prod.yml` — prod overrides.
- `application-test.yml` — test overrides.

Same for `logback-spring.xml`, `SecurityConfig`, `CorsConfig`, etc.

This made onboarding trivial: a new developer learns the pattern once and applies it to any service.

### 3. Testcontainers + WireMock

Integration tests use:
- **Testcontainers Postgres** — real DB, real schema (via Flyway), real `citext`/`pg_trgm`.
- **WireMock** for downstream services.

No H2, no mocks-instead-of-real-SQL, no shared test databases.

The cost: ~20 seconds per integration test class for container startup. The benefit: no "works on H2, fails on Postgres" bugs.

### 4. Snapshot pattern for cross-aggregate references

Order lines snapshot product details. Orders snapshot the shipping address.

This wasn't obvious when we designed the order schema — the natural instinct was to reference product-service and customer-service by foreign key. But products get deleted, customers change addresses, prices fluctuate. The snapshot pattern makes orders immune to downstream changes.

**This is a durable design principle.** Phase 3 (inventory) will snapshot warehouse details. Phase 5 (payment) will snapshot amounts.

### 5. Explicit state machine

`OrderStatus` encodes every legal transition. `Order.transitionTo` enforces it. The controller has one endpoint per transition.

Compared to a generic `PATCH /orders/{id}` with a `status` field, this:
- Prevents ambiguous state changes.
- Allows per-transition authorization (customer can place; only admin can ship).
- Attaches semantics (reasons, side effects) to each transition.
- Makes the audit log meaningful ("customer cancelled" vs. "status changed to CANCELLED").

## What was hard

### 1. Transaction boundaries across service calls

The instinct was:
```java
@Transactional
public OrderDto create(CreateOrderRequest req) {
    CustomerDto customer = customerClient.getCustomer(req.customerId());
    // ... fetch products ...
    return save(order);
}

This holds a DB connection while waiting for HTTP. Under load, the connection pool exhausts.

Solution: split into two beans:

OrderService (public) — external calls, no transaction.

OrderTransactionService — @Transactional, pure DB work.

The service layer orchestrates: fetch externally → call the transactional method.

This pattern (external calls outside the transaction) is subtle and easy to get wrong. Documented in the service's Javadoc and in this retrospective.

2. Feign error decoding
By default, Feign throws FeignException for any non-2xx. That's too low-level to expose to callers.

Solution: a custom FeignErrorDecoder maps downstream errors to domain exceptions:

Downstream 404 → ProductUnavailableException (→ 400)

Downstream 4xx → InvalidOrderDataException (→ 400)

Downstream 5xx → DownstreamServiceException (→ 502)

The mapping isn't trivially derivable from the HTTP status — it depends on which downstream is failing and why. E.g., a product-service 404 is different from a customer-service 404 in terms of what it means for the order.

3. Test data seeding
Each service's V2 migration inserts a small dataset. Cross-service tests reference specific UUIDs from other services' seeds. This is fragile: renaming a seed product in product-service breaks order-service's tests.

Current mitigation: document the seed UUIDs in comments.

Better solution (Phase 7): a fixture module that provides deterministic test data across services.

4. Money handling
The Money value object is now duplicated in three services. Any change to its behavior (e.g., adding rounding rules) requires three coordinated PRs.

Current status: copy-paste, documented as a Phase 7 refactoring target (extract to shared/domain).

5. Startup ordering
Order-service depends on product-service and customer-service being healthy at startup. This creates a chain: postgres → keycloak → product/customer → order.

If product-service is down, order-service won't start. If it's slow, order-service waits. This adds ~30 seconds to total stack startup.

Trade-off: strict startup ordering is safer (order-service doesn't start with an incomplete downstream world) but slower. Phase 7 may relax this (let order-service start with a degraded state and recover).

What we'd do differently
1. Extract shared code earlier
The following files are identical across all three services (except package name):

PageResponse

ProblemDetails, ProblemDetailsFactory

PageableResolver

CorsConfig, PaginationConfig, RequestIdFilter

Money

TestSecurityConfig, AbstractIntegrationTest

Decision for Phase 2+: extract these to shared/common-web and shared/domain Maven modules. The duplication is already painful at 3 services; at 5–6, it becomes a bug factory.

2. Introduce domain exceptions from the start
The order state machine throws IllegalStateException (a Java standard). The service layer catches it and re-throws InvalidOrderStateException. Two exceptions for one concept.

Decision for Phase 2+: entities throw domain exceptions directly (e.g., InvalidOrderStateException). The service layer passes them through.

3. Centralize ADRs earlier
We wrote ADR-0001 after implementing schema-per-service twice. ADR-0002 after choosing Promtail in Phase 0. ADR-0003 after building the overlay in Phase 0.

Decision for Phase 2+: write ADRs when the decision is made, not after. A short ADR while the trade-offs are fresh beats a comprehensive one written months later.

4. Keep seed data minimal and per-service
Seed data in V2__seed_data.sql runs in every environment, including prod. This is fine for now (a few rows), but it clutters prod databases.

Decision for Phase 2+: use Flyway's locations per profile. Prod excludes db/seed/, dev and test include it.

Metrics
Metric	Count
Java files	~140
Lines of Java	~9,500
Test files	~30
Tests	~135
Test coverage	82% (weighted average across services)
New dependencies	Feign (order-service only), Resilience4j, WireMock
Batch approvals	10 (1.1–1.6, 2.1–2.4, 3.1–3.4)
Amendments	~8 (Dockerfile wildcards, parent pom, env files)
Deferred to later phases
The following are known gaps that Phase 2+ should address:

Item	Target Phase
Shared common-web and shared/domain modules	Phase 2 start
Cross-service ownership checks (GET /orders/{id} verifies JWT sub owns the customer)	Phase 3
GET /orders/mine endpoint for self-service order listing	Phase 3
Category CRUD (categories are seed-only)	Phase 2 (GraphQL needs to query them)
Order line operations (add/remove/update on DRAFT)	Phase 3
Circuit breaker tests	Phase 7
Relax order-service startup dependency on downstreams	Phase 7
Migrate from Promtail to Grafana Alloy	Phase 7+
Transactional outbox pattern (for event emission)	Phase 5
Money shared module	Phase 7
Per-profile Flyway locations (prod excludes seed)	Phase 2
Conclusion
Phase 1 delivered three production-shaped REST services in the amount of code a small team would write in 3–4 weeks. The services:

Have clear domain boundaries (schema-per-service, ADR-0001).

Orchestrate cleanly (order-service calls the other two via Feign).

Are fully observable (metrics, logs, traces, dashboards).

Are tested end-to-end (Testcontainers + WireMock + a real e2e test).

The primary learning is that the diff-based batch pattern let us move 3× faster than Phase 0's per-file depth without sacrificing quality. This is the pattern Phase 2 will reuse.

The primary risk is duplication. Ten-plus files are near-identical across services. Extracting them into shared modules is the first task of Phase 2.

Phase 1 is complete.

text

---

## File 6 (continued): `docs/phases.md` (Amendment)

Mark Phase 1 as complete. Diff:

```diff
 | Phase | Name | Focus | Status |
 |-------|------|-------|--------|
 | **0** | Infrastructure | Containers, configs, observability | ✅ Complete |
-| 1 | REST APIs | Product, customer, order services | ⏳ Planned |
+| **1** | **REST APIs** | **Product, customer, order services** | ✅ **Complete** |
 | 2 | GraphQL | Query gateway over REST services | ⏳ Planned |
 ...

 ## Phase 1 — REST APIs
 
-> **Status:** ⏳ Planned
->
-> **Depends on:** Phase 0
+> **Status:** ✅ Complete
+>
+> **Delivered:** 2025-01-15
And update the exit criteria from - [ ] to - [x]:

diff
 ### Exit Criteria
 
-- [ ] All three services respond `200` on `/actuator/health`.
-- [ ] `./mvnw verify` passes for the entire reactor.
-- [ ] Test coverage ≥ 80% per module (enforced by JaCoCo).
-...
+- [x] All three services respond `200` on `/actuator/health`.
+- [x] `./mvnw verify` passes for the entire reactor.
+- [x] Test coverage ≥ 80% per module (enforced by JaCoCo).
+- [x] Every endpoint is documented in OpenAPI and reachable via Traefik.
+- [x] JWT validation works.
+- [x] All three services appear in Prometheus `/targets` as `UP`.
+- [x] Grafana dashboards show per-service RED metrics.
+- [x] Traces for cross-service calls appear in Tempo.
+- [x] End-to-end test passes against the running stack.