# Inventory reservations and bulk recovery

Unary and bulk reservation operations share `ReservationHandler`. Each accepted
item reserves stock, persists its hold and writes its reservation-created outbox
event in one transaction. Bulk calls cross the Spring proxy rather than invoking
a local annotated helper; optimistic retries and replay validation therefore
apply to both entry points.

## Bulk contract

- A stream accepts at most 100 items for one canonical order UUID. Mixed or
  malformed order IDs fail with INVALID_ARGUMENT before any reservation writes.
  Oversized streams fail with RESOURCE_EXHAUSTED before processing.
- Items remain buffered until completion. Client cancellation discards buffered
  work; cancellation observed during processing stops subsequent items.
- Each item commits independently. Successful holds appear in `reservations`;
  insufficient stock, missing stock records, invalid item values and changed or
  terminal retries appear in `failures`. Successes remain committed when another
  item fails. An empty stream returns an empty successful response.
- An infrastructure failure terminates the RPC with a redacted INTERNAL error.
  Earlier successful items remain committed. Retrying the complete stream with
  the same order/SKU/quantity/warehouse values recovers those holds without
  double-reserving stock or duplicating their outbox events.
- Retrying a SKU with a different quantity or warehouse is rejected. Released,
  expired or committed holds cannot silently satisfy a new reserve request.

Clients must retain the order ID and item values when recovering from a lost
response. This is a per-item transaction contract, not an atomic whole-stream
transaction. Existing consumers should account for its 100-item limit and
explicit single-order validation.

## Verification scope

`BulkOperationsHandlerTest` covers buffering, stream bounds, cancellation, order
validation, per-item rejections and redaction. `InventoryPersistenceIT` boots the
production Spring application against PostgreSQL 16.4 and checks committed stock,
holds, audit timestamps and outbox rows. It injects an outbox constraint failure
to verify item rollback and safe replay of earlier commits. Database calls use
the production transaction proxies, without an outer test transaction.

```sh
./mvnw -pl inventory-service -am clean verify
```

`InventoryRpcContractTest` uses generated clients over an in-process transport
with the production service, handlers and exception interceptor/advice. Its
repositories and event publisher are mocked; committed persistence is covered
separately above. `InventoryDomainTest` checks stock conservation and terminal
hold transitions.

The database fixture disables network gRPC serving, the outbox drainer and topic
provisioning. It does not prove deployed issuer validation, mTLS, broker delivery,
all RPC patterns, multi-replica streaming or the complete checkout lifecycle.
Executed results and the unchanged coverage gate are tracked in
[the implementation report](task-implementation-report.md).

The initial focused milestone passed 34 unit/in-process and five PostgreSQL
cases. The November 6 expiry continuation passes a clean five-module static-analysis
build with 110 unit cases (37 inventory and 73 shared) and all eight PostgreSQL
cases. No case fails, errors or skips; four complete static reports contain zero
findings/errors/missing classes. All three coverage policies pass, including
inventory at 91.35% against its unchanged 80% gate. The inventory evidence checker
accepts all 45 service cases. Reports and exact source are retained in
`coverage/inventory-expiry-acceptance-evidence.zip` and JSON; log:
`inventory-expiry-acceptance-verification.log` (4:21, exit 0).

The three added persistence cases execute the production expiry worker against
PostgreSQL. They prove oldest-first bounded processing, recent/terminal exclusion,
event-failure rollback with idempotent retry, and competing workers skipping a
locked reservation before reclaiming it after the owning transaction commits.
See [expiry configuration and remaining acceptance](reservation-expiry.md).
