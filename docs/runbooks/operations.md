# Operations and recovery

Status: isolated acceptance in progress. Procedures below distinguish implemented controls from an executed destination-specific record. Use the signed source and image overlay described in [release](release.md). Never combine production with the development Compose manifest.

## Controlled acceptance

Use a fresh `omp-operations-<unique>` project for every invocation:

```sh
python3 scripts/verify_operations_monitoring.py --project omp-operations-monitor123
sudo python3 scripts/verify_secure_runtime.py --project omp-operations-runtime123
# Build the exact reviewed backup image first.
docker build -t omp/postgres:section7-recovery infrastructure/postgres
python3 scripts/verify_postgres_recovery.py --project omp-operations-restore123
```

The commands refuse existing projects, retain JSON/diagnostics under `coverage/operations`, and remove only their disposable containers/volumes. Monitoring executes twelve trigger fixtures, real routing/resolution, inhibition/silencing and trace retention/redaction. Runtime checks use the production SCRAM/ACL scripts and verify replica-wide quotas and restart persistence. The PostgreSQL drill applies real migrations, takes an encrypted backup, archives later WAL, restores a separate volume to an explicit UTC target, verifies references/pending work and exercises audited replay without external money calls. Required CI rejects missing phases, dirty/mismatched source and failed cleanup.

The [published objectives](../operations-slos.json) precede load/fault measurements. Preserve hardware, workload, raw summaries, recovery times and invariants. A missed objective is a failed result with a prioritized remediation; never reduce the objective after a run. Production receiver delivery and a protected promotion require their own destination-specific evidence when such a destination is supplied.

## Backup and point-in-time restore

Run the `omp-backup.timer` daily full-backup/check service on the production host. Adjust its non-secret deployment environment and verified overlay paths to the active release. The repository uses encrypted full backups plus archived WAL, fourteen-day full retention and a sixty-second archive timeout. Keep the repository and its key independent of the live data volume. Alert on archive errors, backup age and disk pressure; inspect `omp-pgbackrest info --output=json` and `omp-pgbackrest check` before declaring backups healthy.

During an incident:

1. Record the incident, accepted recovery target and last durable provider references. Quiesce application writers and scheduled recovery; provider callbacks must receive retryable failure while their durable store is unavailable. Preserve the original database, WAL and repository.
2. Verify repository/key availability and create an empty separately mounted restore volume. Run `scripts/operations/postgres_restore.sh /restore/data 'YYYY-MM-DD HH:MM:SS+00'` only inside the isolated restore container. The script refuses live paths, symlinks, nonempty destinations and implicit timezones.
3. Start the restored database on a private isolated network with external effects disabled. It pauses at the target. Verify the last included/excluded commit, all reservation/payment/order links, accepted balances and pending outbox/inbox/DLQ rows before promotion.
4. Reconcile payment-provider effects that occurred after the recovery target using the original durable idempotency keys and provider references. Restoring a database does not undo a remote charge/refund. Use the [refund reconciliation contract](../refund-reconciliation.md); never blindly issue a new charge, refund or hold.
5. Promote only after conservation checks pass. Point applications at the restored cluster, resume in a controlled order and verify backlog convergence. Retain the old volume until incident closure. Record measured RPO/RTO and the approver. If verification fails, leave the restore isolated and preserve evidence.

## Broker, DLQ and outbox recovery

Keep three authenticated Kafka brokers, six partitions, replication factor three and minimum ISR two. Restart one broker at a time and wait for ISR recovery. Verify scoped producer authentication and consumption of records acknowledged before and after interruption. Do not enable unclean leader election or reset consumer offsets to conceal lag.

Inspect durable notification receipts and the administrator DLQ/quarantine endpoints before replay. Use an exact item identifier and reason, preserve original event/idempotency identifiers, and verify the immutable replay audit plus final receipt. [Notification verification](../notification-verification.md) covers broker/Redis/database interruption and replay behavior; [payment verification](../payment-verification.md) covers durable receipt and money invariants.

For a failed outbox item, run the versioned `scripts/outbox-replay.sql` with its exact id and a nonempty incident reason against the owning schema. The script records an audit and changes only eligible failed work. Do not delete an outbox row or publish an invented replacement event. The isolated PITR drill verifies replay on the restored cluster while the original remains unchanged.

## Certificate and secret rotation

Follow [gRPC production](../grpc-production.md) for overlapping public trust, atomic leaf replacement and stream reauthorization, and [SOAP operations](../soap-operations.md) for separate key-store/trust-store and WS-Security rotation. Verify hostname, issuer, audience, scope and actual caller identity after rotation. CA signing keys never enter application containers.

For a database password: prepare a new private file without overwriting the active file; change the role through a parameter-safe administrative session with statement/parameter logging disabled; atomically switch its managed file and recreate only the affected service. Confirm old credentials fail and new reads/writes pass. Keep the previous credential only for the bounded rollback window. File bind mounts require recreation after inode replacement; a restart alone can retain an old mounted inode.

Redis replication/admin, notification Pub/Sub, monitoring and quota credentials are separate. Rotate dependent Sentinel/replication clients in a planned sequence; verify discovery, replication and permitted/forbidden ACL commands. The quota store is pinned to Redis 8.6 because Redis 7.4 restricted-ACL AOF replay can lose token-bucket transactions. Preserve that restart regression when updating the lock. Notification credentials cannot access arbitrary channels or administrative commands.

Rotate Kafka SCRAM credentials through the authenticated administrator protocol, update mounted JAAS files and recreate one client/broker at a time. Changing bootstrap files alone does not modify an existing cluster's SCRAM records. Verify authorized publication/consumption and foreign-topic denial. A metrics client-secret rotation also requires restarting the systemd projector so its loaded credential is refreshed; bearer projections refresh automatically and receivers verify their JWTs.

## Alert response

Each alert links here. Check deployment/recent changes and correlate fixed service labels with RED/USE dashboards. Use bounded silences naming the incident and an expiry; resolve the cause before closing the incident.

### ServiceDown

Check private readiness, container restart/OOM state and upstream dependencies. Restore the last compatible signed release when a deployment is responsible. Do not expose a private management port for troubleshooting.

### AvailabilityBudgetFastBurn

Correlate 5m/1h server errors, failing routes and dependency health; stabilize traffic and record affected accepted orders before retrying work.

### AvailabilityBudgetSlowBurn

Investigate the 30m/6h error budget trend, repeated retries and saturation. Assign a remediation before the budget is exhausted.

### LatencyBudgetFastBurn

Follow latency exemplars into Tempo. Inspect bounded downstream deadlines, pool waits and outbox age; keep existing bulkheads/retry bounds.

### JvmHeapSaturation

Inspect heap versus configured container memory, GC and bounded queues. Avoid increasing concurrency during pressure. Capture a redacted diagnostic before restarting one instance.

### DatabasePoolSaturation

Check active/pending connections and database lock waits. Quiesce the expensive caller and preserve transaction/idempotency records.

### RedisBlockedClients

Check authenticated primary/replica/Sentinel state and command latency. Resume clients only after discovery and permission checks pass.

### KafkaConsumerLagHigh

Check broker ISR, scoped credentials and durable consumer receipts. Recover one broker at a time; retain offsets and original identifiers.

### OutboxLagHigh

Check publisher health and exact failed items, repair transport/authentication, then use audited replay. Verify backlog drains within the objective.

### WebhookDlqNonEmpty

Investigate the durable failure classification and destination security policy before an administrator replay. Never bypass SSRF/redirect or HMAC validation.

### TlsCertificateExpiryCritical

Inspect the public leaf projection and execute overlapping-trust rotation. A missing/unreadable public certificate deliberately reports expired.

### TelemetryExportFailures

Check collector/Tempo availability and bounded export queues. Application correctness must survive telemetry loss. Restore collection and verify a new error/slow trace without exporting credentials or request payloads.

The backup timer invokes `scripts/operations/backup_active_release.py` against the accepted persistent promotion record. It uses the retained source/overlay/environment and database UID/GID `70:2000`; run it only after a successful promotion has written `current.json`.


## Isolated application rollback rehearsal

On the disposable operations journey topology, run `python scripts/verify_deployment_rollback.py --project "$COMPOSE_PROJECT_NAME"` before load measurement. It verifies ownership and the healthy source-labelled image, replaces only the stateless product service with a deliberately invalid local database configuration, requires deployment failure, then restores the original immutable image ID and configuration within the declared 300-second recovery objective. Product data, migration history and all stateful/stream-owner containers must remain unchanged. Restoration runs even if the fault check fails; the complete report is `coverage/operations/deployment-rollback.json`.

This executed local drill demonstrates application replacement recovery. Production promotion still requires the signed release workflow and configured environment approvals described in [release operations](release.md); the rehearsal does not create those approvals or publish a release.
