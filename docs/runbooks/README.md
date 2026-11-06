# Runbooks

> **Status:** Procedure catalogue; executed recovery acceptance incomplete
> **Last reviewed:** 2026-11-06

## Overview

Runbooks are **operational procedures** — step-by-step guides for
performing specific tasks on the Order Management Platform. They're
written for operators, SREs, and on-call engineers who need to execute
a procedure correctly under time pressure.

This directory contains bounded development/triage procedures and specifies the
remaining complete production runbooks. Services
are implemented, but complete production deployment, restore, rotation and
rollback procedures still need to be written and exercised. Current development
setup is in [external e2e](../external-e2e.md); bounded operator actions are
documented in [refund reconciliation](../refund-reconciliation.md),
[order cleanup](../order-cleanup.md) and [reservation expiry](../reservation-expiry.md).

---

## What's a Runbook (and What Isn't)

A runbook is a document that:

- **Is reproducible** — the same steps produce the same outcome.
- **Is non-trivial** — more than a single command, or with important
  ordering.
- **Is non-obvious** — an experienced operator would still reference it.
- **Is used rarely or under stress** — during incidents, deployments,
  rotations.

A runbook is **not**:

- A daily operation. "Restart a service" is in the top-level
  [README](../../README.md#common-operations), not here.
- A design document. Those are ADRs.
- A tutorial. Those are in `development/`.
- A reference. Those are component READMEs.

If a procedure is done every day, it doesn't need a runbook — it needs
to be easier to do.

---

## Runbook Status

### Available Now

Available procedures are [dedicated development acceptance](development-acceptance.md)
and [incident triage/recovery selection](incident-response.md), alongside the linked
operator contracts above. None of the
complete production recovery runbooks below has an accepted execution record.
Their original phase labels describe intended scope rather than completed work.

### Planned

The runbooks below are ordered by **operational urgency** — the most
likely to be needed first.

| Runbook | Trigger | Arrives |
|---------|---------|---------|
| [`deploy-production.md`](#deploy-productionmd) | Deploy | Phase 1 |
| [`restore-postgres.md`](#restore-postgresmd) | Incident | Phase 1 |
| [`rotate-secrets.md`](#rotate-secretsmd) | Scheduled | Phase 1 |
| [`verify-backups.md`](#verify-backupsmd) | Scheduled | Phase 1 |
| [`scaling-services.md`](#scaling-servicesmd) | Ad-hoc | Phase 4 |
| [`kafka-topic-recovery.md`](#kafka-topic-recoverymd) | Incident | Phase 5 |
| [`certificate-renewal.md`](#certificate-renewalmd) | Incident | Phase 3 |
| [`soap-fault-diagnosis.md`](#soap-fault-diagnosismd) | Incident | Phase 6 |

Each is described in more detail below.

---

## Planned Runbooks

### `deploy-production.md`

**Trigger:** First-time production deployment, or redeployment.

**Covers:**
- Prerequisites: DNS, ports 80/443 reachable, secrets in
  `/etc/omp/secrets/`.
- Populating `.env.prod`.
- Running the compose overlay.
- Verifying TLS certificates (Let's Encrypt).
- Initializing pgBackRest stanza.
- Verifying all services are healthy.
- Post-deploy smoke tests.

**Key risks:**
- ACME rate limits (test with staging first).
- Secrets not correctly mounted.
- DNS not propagated.

**Rollback:**
- `docker compose -f docker-compose.yml -f docker-compose.prod.yml down`.
- Delete created secrets.
- Remove DNS records.
- Delete Let's Encrypt certs (from `traefik-data` volume).

**Related:**
- `restore-postgres.md` (if you need to restore during/after deploy).
- `rotate-secrets.md` (for credential changes).

---

### `restore-postgres.md`

**Trigger:** Data loss, corruption, or PITR (point-in-time recovery)
need.

**Covers:**
- Identifying the recovery target (timestamp or transaction ID).
- Stopping the Postgres container.
- Restoring from pgBackRest (full + incremental + WAL).
- Promoting the restored cluster.
- Verifying data integrity.
- Restarting dependent services.

**Key risks:**
- Choosing the wrong restore point.
- Restoring over the wrong volume.
- Dependent services writing to the DB before restore completes.

**Rollback:**
- The restore operation itself is destructive to the current cluster.
- The current cluster state is preserved in a backup **before** the
  restore begins. To roll back, restore from that backup.
- The runbook creates this "backup of the current state" as step 1.

**Dry-run:**
- Set `PGBACKREST_DRY_RUN=true` to preview the restore commands
  without executing.
- Full dry-run mode is designed to run against a separate volume.

**Related:**
- `verify-backups.md`.
- `incident-response.md`.

---

### `rotate-secrets.md`

**Trigger:** Scheduled rotation (quarterly), or immediate rotation after
a suspected leak.

**Covers:**
- Rotating Docker secrets (Postgres, Redis, Keycloak, etc.).
- Updating `.env.prod`.
- Rolling restarts of affected services (with zero-downtime ordering).
- Verifying every service reconnects.
- Auditing logs for authentication failures.

**Key risks:**
- Rotating during a deploy → service restart conflicts.
- Missing a dependent service (e.g., a cron job that uses the secret).
- Rotation without updating the pgBackRest repository cipher (a
  special case).

**Rollback:**
- Every rotation step keeps the old secret in memory (via a
  `--previous` file) until verification passes. Rollback restores the
  old secret file and restarts services.

**Schedule:**
- Recommended cadence: quarterly, on a maintenance window.
- Per-secret cadence can differ (e.g., cipher keys annually).

**Related:**
- `deploy-production.md`.
- `incident-response.md`.

---

### `incident-response.md`

**Trigger:** Any production incident (service down, elevated error
rate, user-visible problem).

**Covers:**
- Initial triage (what's broken? user-visible?).
- Priority assessment (severity 1–4).
- Communication (who to notify).
- Investigation flow (dashboards → traces → logs).
- Common failure modes and their fixes.
- Post-incident review structure.

**Key risks:**
- Misdiagnosis leading to worse action.
- Uncoordinated changes during high stress.
- Missing root cause in postmortem.

**Rollback:**
- Not applicable — this is a diagnostic runbook, not a change.

**Related:**
- Every other runbook (this one often routes to them).

---

### `verify-backups.md`

**Trigger:** Scheduled (monthly), or after a backup configuration
change.

**Covers:**
- Checking that pgBackRest has recent full and incremental backups.
- Verifying WAL archiving is up to date.
- Running pgBackRest's `verify` command (checksums).
- Testing a restore to a scratch volume.
- Cleaning up the scratch volume.

**Key risks:**
- Discovering backups are broken during an incident (mitigated by
  monthly cadence).
- Restore test leaving orphaned volumes.

**Rollback:**
- Not applicable — read-only + dry-run restore.

**Schedule:**
- Recommended: monthly on the 1st.
- Can be automated in Phase 7.

**Related:**
- `restore-postgres.md`.

---

### `scaling-services.md`

**Trigger:** Elevated load; capacity planning; scheduled scale-up for
predictable events.

**Covers:**
- Identifying which service is the bottleneck (dashboards, resource
  usage).
- Horizontal scaling of stateless services (`docker compose up
  --scale`).
- Vertical scaling of stateful services (resource limits).
- Verifying load-balancing after scale-out.
- Draining connections for scale-down.

**Key risks:**
- Redis/Kafka becoming the bottleneck after scaling app servers.
- Session stickiness issues (mitigated — stateless + JWT).
- Resource exhaustion on the host (CPU, memory).

**Rollback:**
- Scale-down: reverse `--scale`. Each step is reversible.

**Related:**
- `incident-response.md`.
- `deploy-production.md`.

---

### `kafka-topic-recovery.md`

**Trigger:** Kafka consumer lag accumulates; DLQ fills; offsets lost
after broker failure.

**Covers:**
- Inspecting consumer groups (lag, offsets).
- Replaying from a specific offset.
- Draining a dead-letter queue.
- Recreating topics after broker loss (with partition/replication
  specs).
- Verifying producer/consumer reconnection.

**Key risks:**
- Replaying events that were already processed (idempotency handles
  this — verify).
- Ordering violations during replay.
- Data loss if replication factor is inadequate.

**Rollback:**
- Offset changes can be reversed. Replay cannot (idempotency is the
  mitigation).

**Related:**
- `incident-response.md`.

---

### `certificate-renewal.md`

**Trigger:** Let's Encrypt certificate approaching expiry; renewal
failures.

**Covers:**
- Verifying certificate validity (`openssl s_client`).
- Checking Traefik's ACME logs.
- Understanding common renewal failures (DNS, HTTP-01 challenges,
  rate limits).
- Manually triggering renewal.
- Using DNS-01 challenge as a fallback.

**Key risks:**
- Renewal failing silently → service outage at expiry.
- Rate limit exhaustion (mitigated by monitoring renewal attempts).

**Rollback:**
- Old certificates remain valid until expiry (there's a window to fix
  problems).

**Related:**
- `incident-response.md`.

---

### `soap-fault-diagnosis.md`

**Trigger:** SOAP integration returns faults; adapter can't reach
legacy system.

**Covers:**
- Reading SOAP fault structures (faultcode, faultstring, detail).
- Distinguishing client faults (4xx) from server faults (5xx).
- Common WS-Security failures (expired tokens, wrong credentials).
- Enabling SOAP request/response logging temporarily.
- Circuit breaker behavior during legacy outages.

**Key risks:**
- Enabling verbose SOAP logging in prod (log volume).
- Attempting to fix the legacy system from our side (usually not
  possible).

**Rollback:**
- Not applicable — diagnostic runbook.

**Related:**
- `incident-response.md`.

---

## Runbook Conventions

Every runbook in this directory follows the same structure and style.

### Header

Every runbook starts with:

```markdown
# Runbook: [Title]

> **Status:** Stable | Draft | Deprecated
> **Last reviewed:** YYYY-MM-DD
> **Last executed:** YYYY-MM-DD (or "Never")
> **Estimated duration:** e.g., "15 minutes"
> **Severity if skipped:** e.g., "Data loss" or "Service degradation"
The status field is required (Stable/Draft/Deprecated).
The last executed field is required and distinct from
last reviewed — it's the most recent successful run of the
procedure.
The estimated duration helps operators plan.
The severity if skipped communicates why the runbook matters.

Sections
Every runbook has these sections, in order:

When to use this runbook — a one-paragraph trigger description.

Prerequisites — what must be true before starting.

Steps — numbered, sequential, copy-paste-able commands.

Verification — how to confirm success after the last step.

Rollback — how to undo (or "Not applicable" with reasoning).

Related — links to other runbooks and docs.

Steps format
Each step:

Has a number (1., 2., 2.1, ...).

Has a title in bold.

Has a command in a code block.

Has expected output if the output is diagnostic.

Has a "why" — one sentence explaining why this step exists.

Has an optional "if" clause — for conditional steps.

Example:

markdown
### 3. Verify Postgres is responding

Check that the database accepts connections:

\`\`\`bash
docker compose exec postgres pg_isready -U omp_admin
\`\`\`

Expected output: `/var/run/postgresql:5432 - accepting connections`

If this fails, do not proceed. Check the Postgres logs
(`docker compose logs postgres`) and the disk space
(`df -h`). A full disk is a common cause of connection
refusals.
Dry-run mode
For any runbook that makes destructive changes (restore, delete,
rotate), a dry-run mode is required:

The procedure is designed so that each destructive step has a
--dry-run equivalent.

The dry-run produces the same output as the real run, without
changes.

The operator must complete a successful dry-run before executing
the real run.

If a procedure can't have a dry-run, the runbook says so explicitly,
along with the reasoning.

Rollback
Every runbook has a Rollback section. Options:

Reversible: document the reverse procedure, step by step.

Compensable: describe the action that compensates for the
change (e.g., restore from a backup).

Irreversible: explicitly state "This procedure cannot be
rolled back. To recover, do X." Provide the recovery procedure.

Never leave the Rollback section blank.

Length
A runbook should fit on a few screens. If it's longer than ~300 lines,
it likely covers two procedures — split it.

Runbooks are read under pressure. Long runbooks are dangerous
because they encourage skimming.

Language
Direct, imperative. "Run X." Not "You might want to run X."

Present tense. "The service restarts." Not "The service will
restart."

No hedging. If a step is uncertain, that's a sign the runbook
isn't ready.

No editorial. Opinions on why the architecture is what it is
belong in ADRs, not runbooks.

Adding a Runbook
When you need to add a runbook:

Check if it belongs here. See "What's a Runbook" above. If it's
a daily operation or a design doc, it belongs elsewhere.

Create the file in docs/runbooks/ with the standard header.

Follow the section structure (When to use, Prerequisites,
Steps, Verification, Rollback, Related).

Test the runbook. Actually execute it (in a non-prod
environment if possible) before committing.

Update this README's "Available Now" table.

Update docs/README.md's "Available Now" table.

Commit.

A runbook that hasn't been executed is a draft. Mark it as
Status: Draft until someone completes a successful run.

Maintenance
Runbooks drift faster than other docs because they describe systems
that change. Keep them accurate:

Re-execute monthly (for the ones that make sense to practice).

Update "last reviewed" when you confirm a runbook is accurate.

Update "last executed" after every successful run.

Mark as Deprecated when the procedure no longer applies. Don't
delete — leave a pointer to the replacement.

Reference the runbook in commit messages when the code it
describes changes ("Updated Postgres tuning; see
runbooks/restore-postgres.md").

Feedback
If a runbook is wrong, missing a step, or unclear, open an issue or fix
it directly. A broken runbook is worse than a missing one — it
encourages bad execution under pressure.
