# Reservation expiry

Inventory reservation transitions now carry an optimistic-lock version. Reserve retries must preserve warehouse and quantity and cannot reactivate a terminal hold. Release accepts both RELEASED and EXPIRED as successful no-ops.

Reservation creation, commit, release, and expiry write events to inventory.outbox_event inside the state-change transaction. Kafka delivery uses the shared outbox drainer and has at-least-once semantics; consumers must deduplicate.

The optional expiry worker is disabled by default. Enable with `omp.inventory.expiry.enabled=true` only after the order lifecycle handles expired holds. Configure `omp.inventory.expiry.ttl=PT30M`, `batch-size=100` (1..1000), and `interval-ms=60000`. TTL starts at reservation creation and is not renewed. An accepted draft may therefore lose its hold; order/payment reconciliation remains unfinished.

Each tick selects a bounded oldest-first batch using PostgreSQL FOR UPDATE SKIP LOCKED. Stock release, EXPIRED status, and outbox events commit together. Optimistic locking protects concurrent RPC transitions. Any error rolls back the entire batch and the next tick retries. Multiple workers skip locked reservations; stock contention may still cause a batch rollback.

Monitor `inventory.reservation.expiry.completed` and `inventory.reservation.expiry.failures` (Prometheus underscores). Repeated failures require investigation of missing stock rows, contention, and database availability. Expiry retries on the next tick and has no dedicated operator retry API. Order checkout recovery can discover orphaned resources through its [journal and cleanup worker](order-cleanup.md); shipment now uses [durable fulfillment](order-fulfillment.md), but permanent-failure refund/restock coordination remains unfinished. PostgreSQL concurrency and transactional rollback are now tested below; process-crash and deployed recovery acceptance remain open.

## Executed PostgreSQL acceptance

`InventoryPersistenceIT` now executes the production expiry worker against a
migrated PostgreSQL 16.4 database. Its three expiry cases check oldest-first
bounded batches, recent/terminal exclusion, conserved stock, one outbox event per
expired hold and idempotent empty retries. A rejected outbox insert proves that
the hold and stock changes roll back together and a later tick commits once.
A second worker expires an unlocked hold while another transaction owns the
oldest row, then reclaims that row after commit without double release.

The clean five-module `-pl inventory-service -am -Pstatic-analysis spotless:apply
clean verify` build passes 110 unit and eight integration cases without failures,
errors or skips. Four complete static reports are clean; all three coverage gates
pass, with inventory at 91.35% against its unchanged 80% threshold. Source-bound
reports are retained in `coverage/inventory-expiry-acceptance-evidence.zip` and
JSON; log: `inventory-expiry-acceptance-verification.log` (4:21, exit 0).
These tests leave the scheduled worker disabled in the fixture and call each tick
explicitly. They do not establish accepted-order/payment compensation after
expiry, process-crash recovery or deployed multi-replica acceptance.
