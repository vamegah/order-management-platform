# ADR-0002: Promtail for Log Shipping

> **Status:** Accepted
> **Date:** 2025-01-15
> **Deciders:** Platform team
> **Supersedes:** —
> **Superseded by:** —

## Context

The platform runs on Docker Compose with several containerized services. Each
service writes to stdout/stderr, captured by Docker's `json-file` logging
driver. We need to ship those logs to Loki, where they can be queried from
Grafana.

There are three standard approaches for Docker → Loki log ingestion:

1. **Promtail** — Loki's purpose-built log shipper. Discovers containers via
   the Docker API, tails their log files, parses them with composable
   pipeline stages, and pushes to Loki.

2. **OpenTelemetry Collector's `filelog` receiver** — the Collector reads
   Docker log files directly, applies OTel processors, and exports to Loki.
   Uses the same Collector that already handles traces.

3. **Application-level shipping** — each service ships its own logs (e.g.,
   via a Logback appender that POSTs to Loki). Requires per-service
   configuration and bypasses Docker's stdout.

## Decision

We use **Promtail** as the sole log shipper for the platform.

Promtail runs as a single container (see `docker-compose.yml`). It:
- Uses Docker service discovery to find all containers in the `omp` Compose
  project.
- Tails each container's log files from `/var/lib/docker/containers/`.
- Parses the Docker `json-file` wrapper, then optionally parses the inner
  message as JSON (for services that emit structured logs).
- Attaches low-cardinality labels (`compose_service`, `compose_project`,
  `tier`, `level`) and structured metadata (`trace_id`, `span_id`).
- Pushes batches to Loki's HTTP API.

The OTel Collector handles **traces only**. It does not receive logs.

## Rationale

### Why not the OTel Collector's filelog receiver (option 2)?

- **Promtail is purpose-built for this task.** It has years of production
  hardening specifically for Docker log ingestion (position tracking, JSON
  parsing, label extraction). The OTel Collector's `filelog` receiver is a
  newer, less battle-tested path.
- **Separation of concerns.** Traces and logs have different pipelines,
  different scaling characteristics, and different failure modes. Coupling
  them into one Collector means a log flood can delay trace export (and vice
  versa).
- **Zero impact on the Collector's trace pipeline.** If we need to scale the
  trace pipeline (more Collector replicas, different processors), the log
  pipeline is unaffected.
- **Pipeline stages.** Promtail's stage model (docker → json → timestamp →
  labels → structured_metadata) is more expressive than the OTel Collector's
  log processor chain for Docker-specific patterns.

### Why not application-level shipping (option 3)?

- **Per-service configuration.** Every service would need a Logback/Loki
  appender. Adding a new service requires setting up log shipping in the
  service, not just the platform.
- **Bypasses Docker's stdout.** Logs would arrive in Loki via a different
  path than `docker logs`, making debugging confusing.
- **Sensitive data risk.** Application-level shipping means the app must
  serialize, sanitize, and transmit its own logs — more code paths where PII
  could leak.
- **No position tracking.** If Loki is down, application-level shippers
  typically lose logs. Promtail has file-based position tracking, so logs
  survive Loki outages.

## Consequences

### Positive

- **Proven tooling.** Promtail is the standard for Docker → Loki.
- **Single shipper, single config.** One `promtail-config.yml` for the entire
  platform. Adding a new service requires no log-shipping configuration
  (Promtail discovers it automatically).
- **Trace correlation works.** Promtail extracts `trace_id` and `span_id`
  from JSON logs and attaches them as structured metadata. Grafana's Loki
  datasource links them to Tempo. This is the mechanism behind the "click a
  log line, see the trace" experience.
- **Deployment-independent.** The same Promtail config runs in dev and prod.
- **No application changes.** Services log to stdout in a conventional
  format. No special appenders.

### Negative

- **One more container.** ~30MB RSS, negligible CPU. Acceptable.
- **Docker socket access.** Promtail needs read access to `/var/run/docker.sock`
  for service discovery, and read access to `/var/lib/docker/containers/` for
  log files. Mitigated: socket is mounted read-only, log files are read-only.
- **Structured metadata requires Loki 3.0+.** Older Loki versions don't
  support structured metadata, so the `trace_id` correlation would have to
  fall back to labels (high cardinality) or a different mechanism.
- **Promtail is being deprecated.** Grafana Labs announced that Promtail is
  being replaced by **Alloy** (a unified telemetry agent). Migrating from
  Promtail to Alloy is expected to be a Phase 7 or later task.

### Neutral

- **One log pipeline, one place to debug.** If logs aren't reaching Loki, the
  cause is either Promtail or Loki, not "some combination of application
  loggers and a shipper."

## Implementation

- Promtail container: `docker-compose.yml`, service `promtail`.
- Promtail config: `infrastructure/promtail/promtail-config.yml`.
- Loki config: `infrastructure/loki/loki-config.yml`.
- Grafana datasource: `infrastructure/grafana/provisioning/datasources.yml`
  (Loki + derived fields for `trace_id`).
- Application log format: `service/src/main/resources/logback-spring.xml`
  (Logstash JSON encoder).

## Related Decisions

- ADR-0001: Schema-per-service in a single Postgres instance.
- [ADR-0003](0003-dev-prod-compose-overlay.md): Compose overlays.
- [ADR-0005](0005-grpc-for-internal-services.md): internal gRPC.

## References

- Promtail documentation: https://grafana.com/docs/loki/latest/send-data/promtail/
- Grafana Alloy (successor to Promtail): https://grafana.com/docs/alloy/latest/