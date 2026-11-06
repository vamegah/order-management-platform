# ADR-0001: Schema-per-Service in a Single Postgres Instance

> **Status:** Accepted
> **Date:** 2025-01-15
> **Deciders:** Platform team
> **Supersedes:** —
> **Superseded by:** —

## Context

The Order Management Platform has multiple business services
(`product-service`, `customer-service`, and — in Phase 3 — `order-service`,
`inventory-service`, `payment-service`). Each owns a distinct domain and its
data model.

There are three standard ways to organize persistence for such a system:

1. **Shared database, shared schema.** All services read and write tables in
   the same namespace. Simple, but offers no isolation: one service's bug or
   migration can corrupt another's data.

2. **Shared database, one schema per service.** All services share a single
   Postgres instance and database, but each service owns its own schema. Data
   is logically isolated. Cross-service reads are possible with grants but
   discouraged.

3. **Database-per-service.** Each service has its own Postgres instance (or
   its own database on a shared instance). Maximum isolation. Requires more
   operational work (backups, migrations, connection management per service).

## Decision

We chose **option 2: one Postgres instance, one database (`omp`), one schema
per service.** Each service has:

- A dedicated schema (`products`, `customers`, `orders`, `inventory`,
  `payments`).
- A dedicated role (`products_svc`, `customers_svc`, etc.) that owns the
  schema.
- A dedicated connection user configured in the service's datasource.
- No access to other services' schemas unless explicitly granted.

A separate `reporting` schema aggregates cross-service views for BI and
analytics. No application service writes to it.

## Rationale

### Why not shared schema (option 1)?

- **No isolation.** A migration in `product-service` could accidentally alter
  a table used by `customer-service`.
- **No clear ownership.** When a table has a bug, it's ambiguous which team
  owns it.
- **No path to isolation.** Migrating to schema-per-service or DB-per-service
  later requires disruptive data movement.

### Why not database-per-service (option 3)?

- **Operational overhead for little gain.** On a single host (Phase 0's
  deployment), per-database isolation is mostly bookkeeping. Schema-per-service
  gives us the same logical isolation with less operational complexity.
- **Backup simplicity.** One pgBackRest stanza, one WAL stream, one set of
  retention policies. Per-service databases would need N backup configs.
- **Cross-service reads.** Some legitimate reads (reporting) span multiple
  services. Schema-per-service makes those reads a `GRANT SELECT`; DB-per-service
  requires FDW or API-based aggregation.

### Why schema-per-service (option 2)?

- **Logical isolation.** Each service's role can only see its own schema by
  default. Cross-schema grants are explicit, documented, and rare.
- **Ownership is clear.** The schema is named after the service that owns it.
  The role is too. Anyone can trace "who owns `customers.customer`?" in one
  step.
- **Migration path to DB-per-service.** If a service's load or isolation
  requirements grow, its schema can be `pg_dump`'d, restored into its own
  database, and the JDBC URL changed. The application code doesn't change
  because it never crosses schema boundaries (except via explicit grants).
- **Compatible with Flyway.** Each service has its own `flyway_schema_history`
  table in its own schema. Migrations are independent.

## Consequences

### Positive

- **Isolation at the schema level.** Each service connects as a role that
  cannot see other services' schemas without an explicit grant.
- **Clear ownership.** Schema and role names match the service.
- **Independent migrations.** Each service's Flyway history is separate;
  migration conflicts across services are impossible.
- **Cross-service reads are possible** (via explicit grants) but not
  encouraged. The `reporting_svc` role demonstrates the pattern for BI.
- **Simple operations.** One Postgres instance, one pgBackRest stanza, one
  set of backups.
- **Migratable.** Any service can move to its own database later without
  application code changes.

### Negative

- **Single point of failure.** All services depend on the same Postgres. If
  Postgres goes down, the whole platform is affected. Mitigated by high
  availability at the Postgres level (Phase 7: consider Patroni or a managed
  service).
- **Shared resource contention.** A slow query in one service can affect
  others (I/O, locks, memory). Mitigated by per-role `statement_timeout`,
  `lock_timeout`, and monitoring.
- **Temptation to skip the API.** With schemas accessible on the same
  instance, developers may be tempted to `SELECT FROM other_schema.table`
  instead of calling the owning service's API. Enforced by convention and
  code review; a stricter Phase 7 could remove all cross-schema grants except
  for `reporting_svc`.

### Neutral

- **Connection pool per service.** Each service has its own Hikari pool. At
  10 connections each and 5–7 services, that's 50–70 connections — well within
  Postgres's `max_connections` (200 in prod).

## Implementation

- Schema creation: `infrastructure/postgres/init/01-schemas.sql`.
- Role creation and grants: same file.
- Cross-schema grants (reporting, specific reads): same file.
- Per-service Flyway migrations: `service/src/main/resources/db/migration/`.
- Per-service datasource: `spring.datasource.url` in `application.yml` with
  `currentSchema=<schema>,public`.

## Related Decisions

- [ADR-0002](0002-promtail-for-logs.md): Promtail for log shipping.
- [ADR-0003](0003-dev-prod-compose-overlay.md): Compose overlays.
- [ADR-0005](0005-grpc-for-internal-services.md): internal gRPC.

## References

- Postgres schema documentation:
  https://www.postgresql.org/docs/16/ddl-schemas.html
- Original ADR format (Michael Nygard):
  https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions