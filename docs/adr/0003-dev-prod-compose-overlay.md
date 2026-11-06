# ADR-0003: Dev + Prod Compose Overlay Pattern

> **Status:** Accepted
> **Date:** 2025-01-15
> **Deciders:** Platform team
> **Supersedes:** —
> **Superseded by:** —

## Context

The platform runs on Docker Compose. It has two deployment targets:

- **Local development** — a developer's laptop, optimized for fast
  iteration, readable logs, and safe defaults.
- **Production** — a single host (currently), optimized for security,
  resilience, and operational safety.

These targets have conflicting requirements:

| Concern | Development | Production |
|---------|-------------|------------|
| TLS | None (localhost) | Required, auto-renewed |
| Secrets | `.env` file | Docker secrets, external key store |
| Resource limits | None | CPU/memory caps on every service |
| Replicas | 1 | 2+ for stateless services |
| HA | None | Postgres WAL archiving, Kafka RF=3, Redis Sentinel |
| Log verbosity | INFO, human-readable | WARN, structured JSON |
| Admin tools | pgAdmin, Kafka UI exposed | Not deployed |
| Backup | None | pgBackRest sidecar |
| Docker socket | Mounted directly | Via read-only proxy |

We need an approach that keeps dev and prod **synchronized** (same services,
same versions, same configs) while allowing these differences.

## Decision

Use **two Docker Compose files**:

- `docker-compose.yml` — the **base** configuration. Runnable on its own
  on a laptop. Every service is defined here with dev-friendly values.
- `docker-compose.prod.yml` — the **overlay**. Applied on top of the base:
    docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d

    
The overlay **only adds or overrides** keys. It cannot remove services,
volumes, or networks defined in the base.

## Rationale

### Alternatives considered

**A. One compose file with profiles.** Compose profiles (`--profile prod`)
let you activate different services. Rejected because profiles are
service-level, not override-level: you can't easily change a service's
image, add resource limits, or swap a mount based on the profile. You end
up with duplicate service definitions under different profiles.

**B. Two completely independent compose files.** Copy the entire stack for
each environment. Rejected because the files drift. A change to the base
(e.g., adding a new service) has to be manually replicated in the prod file,
and it's easy to forget.

**C. Docker Swarm.** A single YAML file with `deploy:` blocks, run in Swarm
mode. More powerful, but adds a scheduler and a different mental model.
Overkill for a single-host deployment. Also complicates the developer
workflow (must run `docker swarm init`).

**D. Kubernetes.** The eventual scaling answer for a multi-host deployment.
Too heavy for a Phase 0/1 project. The compose files remain a useful
reference architecture when we do migrate.

### Why base + overlay is correct

- **Compose-native.** Overlays are a first-class Compose feature, not a
workaround.
- **DRY.** The base is defined once. The overlay only expresses deltas.
- **Runnable base.** A new developer runs `docker compose up -d` and has a
working stack — no need to also apply the prod file.
- **Reviewable deltas.** The prod file's diff against base is small and
focused: TLS, resource limits, secrets. Easy to review and reason about.
- **Deterministic merge.** Compose merges overlays predictably: maps
(environment, labels) are merged, lists (ports, volumes) are replaced.
We document this behavior in the overlay file.

## Consequences

### Positive

- **Synchronized services.** Both environments run the same container
images, at the same versions.
- **Fast local iteration.** Dev is minimal — no TLS, no resource
constraints, no HA. Startup is ~90 seconds.
- **Safe production.** Overlay adds the hardening that production requires
without affecting dev.
- **Clear separation of concerns.** Dev file: "what services exist."
Prod file: "how production differs."
- **Reviewable.** A change to a service in dev is immediately visible as
affecting prod too (both files reference the same service). A change
*only* to prod is a deliberate hardening decision.

### Negative

- **Compose-specific quirks.** List-type fields (ports, volumes) are
*replaced* by the overlay, not merged. This means if a service has three
volumes in dev and the overlay wants to add a fourth, the overlay must
re-list all four. We documented this in the overlay's header comment and
every overridden list repeats the full set.
- **Cannot remove services.** The overlay cannot delete a service defined
in the base. The `pgbackrest` sidecar is added by the overlay (fine);
`pgadmin` and `kafka-ui` are defined in the base and disabled in prod via
`deploy.replicas: 0` (a workaround, not a deletion).
- **One-command deployment is two-command.** `docker compose up -d` (dev)
vs. `docker compose -f docker-compose.yml -f docker-compose.prod.yml
--env-file .env.prod up -d` (prod). We wrap the latter in a `Makefile`
target in Phase 7 to keep it ergonomic.

### Neutral

- **Future multi-host.** When we migrate to Kubernetes, the compose files
become a reference for what the manifests should contain. The overlay
pattern translates to Helm values (base + prod values file).

## Implementation

- Base: `docker-compose.yml` (all services, dev values).
- Overlay: `docker-compose.prod.yml` (TLS, HA, limits, secrets).
- Env files: `.env.example` (dev), `.env.prod.example` (prod).
- Documentation: top-level `README.md`, prod deployment section.

## Related Decisions

- ADR-0001: Schema-per-service.
- ADR-0002: Promtail for logs.
- [ADR-0005](0005-grpc-for-internal-services.md): internal gRPC.

## References

- Docker Compose merge rules:
https://docs.docker.com/compose/multiple-compose-files/merge/
- Compose specification:
https://compose-spec.io/