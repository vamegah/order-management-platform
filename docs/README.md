# Documentation

Project documentation for the Order Management Platform.

This directory contains architecture notes, decision records, runbooks,
and contributor guides. It's the deeper layer beneath the top-level
[`README.md`](../README.md).

---

## Documentation Structure

| Directory | Contains | Audience |
|-----------|----------|----------|
| Root Markdown contracts | Implemented behavior, diagrams, verification and contributor commands | Contributors, reviewers |
| [`adr/`](adr/README.md) | Accepted design decisions with current implementation refinements | Contributors, reviewers |
| [`runbooks/`](runbooks/README.md) | Operational procedure catalogue; execution acceptance incomplete | Operators, SREs |
| [`retrospectives/`](retrospectives/README.md) | Historical implementation records, superseded by current acceptance evidence | Contributors |

The [architecture guide and API catalogue](architecture/README.md) describe the
implemented topology and trust boundaries. Further production/development guides
remain documentation work; available procedures and verification notes are linked below.

---

## Available Now

Current implementation contracts, verification guidance and operational notes:

| Document | Description |
|----------|-------------|
| [`architecture/README.md`](architecture/README.md) | Current topology, trust boundaries, event/data flow and API catalogue |
| [`runbooks/development-acceptance.md`](runbooks/development-acceptance.md) | Dedicated setup, external/default verification ordering and evidence preservation |
| [`runbooks/incident-response.md`](runbooks/incident-response.md) | Bounded triage and links to implemented recovery actions |
| [`task-implementation-report.md`](task-implementation-report.md) | Executed verification evidence and remaining blockers |
| [`test-phases.md`](test-phases.md) | Unit, container integration and e2e commands |
| [`quality-gates.md`](quality-gates.md) | CI, coverage evidence, SpotBugs, dependency policy, SBOM and image packaging |
| [`contract-evolution.md`](contract-evolution.md) | Protobuf/SOAP ownership, published fixtures and exact PR-base compatibility checks |
| [`external-e2e.md`](external-e2e.md) | Independent external order/refund/WebSocket journeys, mTLS clients and development identity setup |
| [`websocket-client-guide.md`](websocket-client-guide.md) | Packaged client, expiry-aware reconnect, identity isolation and canonical-state recovery |
| [`grpc-stream-topology.md`](grpc-stream-topology.md) | Supported single-replica streams, deployment gate and recovery limits |
| [`shared-module-audit.md`](shared-module-audit.md) | Domain, web, event/outbox and protobuf fixes and verification scope |
| [`batch-read-contract.md`](batch-read-contract.md) | Product/customer batch reads and GraphQL batching |
| [`catalog-customer-verification.md`](catalog-customer-verification.md) | Product/customer persistence, HTTP security and container verification |
| [`graphql-production-policy.md`](graphql-production-policy.md) | Query limits, caching, persisted-query and production settings |
| [`order-state-contract.md`](order-state-contract.md) | Order transitions and payment-backed confirmation/shipment |
| [`inventory-reservations.md`](inventory-reservations.md) | Bulk reservation transaction boundaries, replay and verification scope |
| [`order-verification.md`](order-verification.md) | Order access policy, persistence repairs and integration verification |
| [`order-fulfillment.md`](order-fulfillment.md) | Durable inventory commits, pending shipment, worker identity and retries |
| [`order-cleanup.md`](order-cleanup.md) | Durable checkout journal, cleanup, worker identity and retries |
| [`reservation-expiry.md`](reservation-expiry.md) | Inventory expiry and retry operations |
| [`payment-verification.md`](payment-verification.md) | Payment startup repairs and PostgreSQL, HTTP/provider and stream verification scope |
| [`payment-idempotency-contract.md`](payment-idempotency-contract.md) | Payment creation consistency and replay contracts |
| [`payment-refunds.md`](payment-refunds.md) | Provider-backed refund intents, webhook completion and cancellation cleanup |
| [`refund-reconciliation.md`](refund-reconciliation.md) | Audited administrator reconciliation of unknown provider references |
| [`soap-fault-contract.md`](soap-fault-contract.md) | Structured SOAP faults and public error mapping |
| [`banking-verification.md`](banking-verification.md) | Banking coverage, application startup and WS-Security boundary verification |
| [`websocket-client-guide.md`](websocket-client-guide.md) | WebSocket connection and subscription behavior |
| [`phases.md`](phases.md) | Phase plan; use the implementation report for verified status |
| [`runbooks/README.md`](runbooks/README.md) | Runbooks index; executed recovery acceptance remains pending |

The broader architecture and recovery catalogue below remains planned. A document
or configuration file alone does not establish successful deployment or an
executed recovery exercise. [The backlog](../TASKS.md) tracks remaining acceptance.

The configs themselves are heavily commented (see
[`infrastructure/`](../infrastructure/)), and each complex config
directory has its own README:

| Component | README |
|-----------|--------|
| pgAdmin | [`infrastructure/pgadmin/README.md`](../infrastructure/pgadmin/README.md) |
| Grafana | [`infrastructure/grafana/README.md`](../infrastructure/grafana/README.md) |

---

## Remaining documentation

The services already exist. The architecture/development guides below still need
to consolidate their current contracts; historical phase labels describe original
scope, not delivery dates or acceptance.

### Architecture

| Document | Will cover | Arrives with |
|----------|-----------|--------------|
| `architecture/overview.md` | Full system diagram, data flows, deployment topologies | Phase 1 |
| `architecture/data-model.md` | Entity relationships, schema ownership, cross-service reads | Phase 1 |
| `architecture/observability.md` | How metrics, logs, and traces flow through the platform | Phase 1 |
| `architecture/security.md` | Authentication, authorization, secrets, TLS | Phase 3 |

### ADRs (Architecture Decision Records)

Twelve decisions are already available in [the ADR index](adr/README.md), covering
schemas, Compose, shared modules, gRPC, mTLS, compensation, events, subscriptions,
real-time fan-out and outboxes. A separate webhook-signature/rotation decision is
still needed. Accepted designs do not establish deployment or operational success.

### Runbooks

| Document | Will cover | Arrives with |
|----------|-----------|--------------|
| `runbooks/deploy-production.md` | First-time prod deployment, DNS, ACME, stanza-init | Phase 1 |
| `runbooks/restore-postgres.md` | PITR from pgBackRest, dry-run, cutover | Phase 1 |
| `runbooks/rotate-secrets.md` | Rotate every Docker secret with minimal downtime | Phase 1 |
| `runbooks/incident-response.md` | Triage flow: what to check first, escalation | Phase 1 |
| `runbooks/scaling-services.md` | Horizontal scaling of stateless services | Phase 4 |
| `runbooks/kafka-topic-recovery.md` | Rebuilding consumer offsets, DLQ replay | Phase 5 |
| `runbooks/certificate-renewal.md` | Let's Encrypt failures and manual renewal | Phase 3 |

### Development

| Document | Will cover | Arrives with |
|----------|-----------|--------------|
| `development/adding-a-service.md` | The 7-step checklist for adding a new service | Phase 1 |
| `development/testing-guide.md` | Unit, integration, contract, E2E test patterns | Phase 1 |
| `development/debugging.md` | Common failure modes, log queries, trace access | Phase 1 |
| `development/local-troubleshooting.md` | Docker Desktop issues, port conflicts, resource limits | Phase 1 |

---

## Documentation Conventions

### File naming

- **Kebab-case** for all files: `restore-postgres.md`, not `RestorePostgres.md`.
- **Descriptive** filenames: `rotate-secrets.md`, not `secrets.md`.
- **Numbered ADRs** with a 4-digit zero-padded prefix: `0001-schema-per-service.md`.
- **No dates in filenames** — git tracks modification times.

### File structure

Every document starts with:

```markdown
# Title

> **Status:** Stable | Draft | Deprecated
> **Last reviewed:** YYYY-MM-DD

## Overview

One paragraph explaining what this document covers and who should read it.

## ... (body)

The status field is required:

Stable — accurate, reviewed, safe to rely on.

Draft — in-progress, may change.

Deprecated — no longer accurate; retained for historical context.

The last reviewed date lets readers judge freshness without digging
into git history. It's updated when a doc is confirmed accurate, not on
every edit.

Content style
Explain why, not just what. A runbook that says "run this command"
is a script. A runbook that says "run this command because X" is a
document. We write the latter.

Tables over prose for structured data. Lists of fields, options,
and parameters belong in tables.

Copy-paste-able examples. No placeholders like <your-value> unless
the value is genuinely variable and clearly marked.

Link, don't duplicate. If info exists elsewhere, link to it rather
than copying. Copies drift; links don't.

Mark speculative content. Anything not yet implemented is prefixed
"(Phase N+)" or "(planned)".

Keep it short. If a doc exceeds ~500 lines, it probably covers two
topics. Split it.

Code blocks in documentation
Always specify a language for syntax highlighting: ```bash,
```yaml, ```sql.

Include the full command, not a fragment. Readers copy/paste blocks.

Show expected output when it's diagnostic (e.g., "you should see
ready").

Mark destructive commands explicitly. docker compose down -v
should be surrounded by warnings, not buried in a list.

Diagrams
ASCII diagrams in the file — they diff cleanly, render everywhere,
and don't require external tools.

Avoid external images unless truly necessary. A PNG in git is
opaque; an ASCII diagram is reviewable.

Keep them focused. A diagram with 30 boxes is noise. Split into
multiple diagrams if needed.

Adding a new document
Decide the directory. Architecture? ADR? Runbook? Development?

Create the file with the required header.

Add it to this file's appropriate table.

If it's significant, add it to the top-level README.md's
Documentation Index.

Commit — documentation changes go in the same PR as code changes
when the doc is about that code.

When NOT to write a doc
One-off commands. A command you'll run once doesn't need a doc.
Put it in a comment or a commit message.

Things git tracks. History, blame, and commit messages are their
own record. Don't duplicate them in a CHANGELOG unless the project is
externally released.

Things the code says. If the code is clear (well-named functions,
good comments), it doesn't need a doc. Docs are for things the code
can't say: why, when, what to do when.

Maintenance
Documentation drifts. These are the habits that keep it accurate:

Review docs when the code they describe changes. A change to
docker-compose.yml should prompt a review of any runbook that
references it.

Update the "last reviewed" date when you confirm a doc is still
accurate — even if you don't change it.

Mark docs as Deprecated when they no longer apply. Don't delete
them silently; the historical record has value.

Fix broken links as soon as they're noticed. Broken docs undermine
trust in all docs.

Prefer revising to appending. A doc that grows by accretion becomes
incoherent. Rewrite sections when they need substantial changes.

Feedback
If a doc is wrong, missing, or unclear, open an issue or fix it directly.
Documentation bugs are bugs.
