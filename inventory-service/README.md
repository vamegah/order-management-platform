# Inventory Service

gRPC-only service for stock levels and reservations. Part of the
[Order Management Platform](../README.md).

**This service has no REST API.** It exposes a gRPC server on port 9091
(application traffic) and a management HTTP server on port 9092 (actuator
endpoints for monitoring).

The servlet runtime creates the production JWT decoder and management listener;
the application HTTP connector is disabled. Health/info are public, Prometheus
requires a valid signed JWT, and other HTTP routes are denied. Signed-token
configuration tests cover issuer, signature and expiry validation. Runtime
images use Jammy for the bundled gRPC native TLS library; deployed acceptance is
tracked in [the implementation report](../docs/task-implementation-report.md).

## Why gRPC-only?

inventory-service is called exclusively by other services (order-service,
in this phase). It has no external clients. gRPC is a better fit for
internal service-to-service communication because:

- **Strongly typed.** The `.proto` file is the contract; clients and
  servers are generated from it.
- **Efficient.** Binary serialization, HTTP/2 multiplexing.
- **Streaming.** The `StreamStockUpdates` RPC pushes real-time updates
  without polling.
- **Deadlines.** gRPC has built-in deadline propagation.

The external API is REST/GraphQL (Phase 1 services + graphql-gateway).
gRPC is used only where it earns its keep (see
[ADR-0005](../docs/adr/0005-grpc-for-internal-services.md)).

## Service Definition

The gRPC API is defined in
[`shared/proto/src/main/proto/inventory/inventory.proto`](../shared/proto/src/main/proto/inventory/inventory.proto).

### RPC Methods

| Method | Type | Purpose |
|--------|------|---------|
| `GetStockLevel` | Unary | Query current stock for a SKU in a warehouse |
| `CheckAvailability` | Unary | Check if N units are available |
| `ReserveStock` | Unary | Reserve N units for an order |
| `ReleaseReservation` | Unary | Release a reservation (order cancelled) |
| `CommitReservation` | Unary | Commit a reservation (order shipped) |
| `GetReservation` | Unary | Fetch a reservation by ID |
| `BulkReserve` | Client streaming | Reserve multiple SKUs in one call |
| `StreamStockUpdates` | Server streaming | Subscribe to real-time stock changes |

### Error Model

gRPC status codes (not HTTP status codes):

| Code | Trigger |
|------|---------|
| `NOT_FOUND` | SKU or reservation doesn't exist |
| `FAILED_PRECONDITION` | Insufficient stock, or illegal state transition |
| `INVALID_ARGUMENT` | Malformed request (bad UUID, negative quantity) |
| `UNAUTHENTICATED` | Missing or invalid JWT |
| `INTERNAL` | Unexpected server error |

See the `.proto` file's header comment for the complete list.

## Architecture
order-service ──gRPC──► inventory-service ──JDBC──► Postgres (inventory schema)
│
└──OTLP──► OTel Collector ──► Tempo


### Concurrency Model

`StockLevel` uses **optimistic locking** via a JPA `@Version` column.
Concurrent reservations against the same SKU conflict at commit; the second
transaction retries (up to 3 times) and either succeeds or fails with
`FAILED_PRECONDITION` if stock is now insufficient.

**Why optimistic?** Inventory is read-heavy and write-light. Optimistic
locking avoids holding row locks during reads. Retries are cheap under
normal contention.

### Idempotency

`ReserveStock`, `ReleaseReservation`, and `CommitReservation` are
idempotent:

- **Reserve** on `(orderId, sku)`: calling twice returns the existing
  reservation, no duplicate.
- **Release** on an already-RELEASED reservation: no-op, returns current
  state.
- **Commit** on an already-COMMITTED reservation: no-op.

This is essential because gRPC clients may retry on transient network
failures.

## Environment Variables

| Variable | Default (dev) | Purpose |
|----------|---------------|---------|
| `SPRING_PROFILES_ACTIVE` | `local` | Spring profile |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/omp?currentSchema=inventory,public` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `inventory_svc` | DB role |
| `SPRING_DATASOURCE_PASSWORD` | (from `.env`) | DB password |
| `OIDC_ISSUER_URI` | `http://keycloak.localhost/realms/omp` | JWT issuer |
| `OIDC_JWK_SET_URI` | `http://keycloak:8080/...` | JWKS endpoint |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4317` | Trace exporter |
| `PROMETHEUS_ENV` | `dev` | Metrics environment label |
| `GRPC_PORT` | `9091` | gRPC server port |
| `GRPC_REFLECTION_ENABLED` | `true` (dev), `false` (prod) | Enable grpcurl reflection |
| `MANAGEMENT_PORT` | `9092` | Actuator HTTP port |

## Development

### Build

```bash
./mvnw -pl inventory-service clean package

Run locally
Requires Postgres + Keycloak from the Docker stack:

bash
docker compose up -d postgres keycloak otel-collector
./mvnw -pl inventory-service spring-boot:run
The gRPC server starts on port 9091. The management server starts on 9092.

Verify it's up
bash
# Management HTTP health check
curl http://localhost:9092/actuator/health
# Expected: {"status":"UP"}

# Prometheus metrics
curl http://localhost:9092/actuator/prometheus | grep grpc_server
# Expected: grpc_server_* metrics (one per RPC method)
Calling the Service
With grpcurl (recommended for development)
grpcurl is a curl-like CLI for gRPC. Install it separately:

bash
brew install grpcurl
# or, on Linux: go install github.com/fullstorydev/grpcurl/cmd/grpcurl@latest
Get a JWT:

bash
TOKEN=$(curl -s -X POST http://keycloak.localhost/realms/omp/protocol/openid-connect/token \
  -d "grant_type=password" -d "client_id=postman" \
  -d "username=alice" -d "password=alice" | jq -r '.access_token')
Get stock level:

bash
# The gRPC port is internal-only. Use `docker compose exec` to reach it
# from inside the app network, or port-forward for local testing.
docker compose exec inventory-service \
  grpcurl -plaintext \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"sku": "LAPTOP-PRO-14", "warehouse_id": "WH-1"}' \
    localhost:9091 omp.inventory.InventoryService/GetStockLevel
Expected output:

json
{
  "sku": "LAPTOP-PRO-14",
  "warehouseId": "WH-1",
  "quantityOnHand": 100,
  "quantityReserved": 0,
  "quantityAvailable": 100,
  "lastUpdatedEpochMillis": "1705334400000"
}
List services (reflection):

bash
docker compose exec inventory-service \
  grpcurl -plaintext localhost:9091 list
# Expected:
#   grpc.health.v1.Health
#   grpc.reflection.v1alpha.ServerReflection
#   omp.inventory.InventoryService
Describe a method:

bash
docker compose exec inventory-service \
  grpcurl -plaintext localhost:9091 describe omp.inventory.InventoryService.ReserveStock
From Java (in another service)
See order-service/src/main/java/.../clients/ (added in Batch 7.4).

From other tools
Postman supports gRPC (import the .proto file).

BloomRPC — a GUI gRPC client.

Kreya — a modern gRPC client (successor to BloomRPC).

Testing

Unit tests exercise reservation retries, recovery authorization, expiry and the
bounded bulk-stream lifecycle. PostgreSQL integration tests boot production
Spring transaction proxies and check committed stock/hold/outbox behavior,
including rollback and replay after an outbox write failure.

See [reservation and bulk recovery semantics](../docs/inventory-reservations.md)
for the per-item commit contract and exact verification limits. Deployed JWT,
mTLS, all RPC patterns and multi-replica stream acceptance remain open.

```sh
./mvnw -pl inventory-service -am clean verify
```

Observability
Metrics: /actuator/prometheus on the management port (9092).
Includes grpc_server_* metrics for every RPC (count, duration, status).

Logs: JSON to stdout — Promtail ships to Loki.

Traces: exported via OTLP to the OTel Collector.
gRPC calls carry traceparent in metadata.

Known Limitations
Server-streaming subscriptions don't fan out across replicas. Each
replica maintains its own in-memory subscription registry. If
inventory-service runs with 2 replicas, a subscriber on replica A won't
receive updates triggered on replica B.

The supported production overlay constrains this service to one replica with
stop-first updates and rollbacks. See [stream topology and recovery](../docs/grpc-stream-topology.md); cross-replica fan-out remains unimplemented.

The stream is a live hint with dropped updates under backpressure and no replay
cursor. Reconnecting and slow consumers must reconcile through stock reads;
process-replacement acceptance remains open.

An opt-in bounded expiry worker is implemented and disabled by default. It
expires ACTIVE reservations with stock release and outbox events in the same
transaction. Enable it only after reviewing order handling for expired draft
holds. Configuration, metrics and remaining crash/concurrency acceptance are in
[reservation expiry](../docs/reservation-expiry.md).

gRPC rate limiting remains open; do not treat transport authentication as a
resource-consumption limit.

Troubleshooting
Client gets UNAVAILABLE.

The server isn't reachable. Check:

bash
docker compose ps inventory-service
docker compose logs inventory-service | tail -50
Verify the gRPC port is listening:

bash
docker compose exec inventory-service netstat -tlnp | grep 9091
Client gets UNAUTHENTICATED.

The JWT is missing or invalid. In grpcurl:

bash
-H "Authorization: Bearer $TOKEN"
Verify the token is not expired (>5 minutes old).

FAILED_PRECONDITION on reserve.

Insufficient stock, or an illegal state transition (e.g., releasing an
already-COMMITTED reservation). The description includes the specific
reason.

Slow streaming updates.

Each replica's subscription registry is independent. If you subscribe to
an event triggered on another replica, you won't see it. See "Known
Limitations."

Reflection not working.

In prod, reflection is disabled. In dev, verify:

bash
docker compose exec inventory-service \
  grpcurl -plaintext localhost:9091 list
If this fails, check GRPC_REFLECTION_ENABLED=true in the environment.

Related Documentation
Platform README

ADR-0005: gRPC for internal services

Shared proto definitions

gRPC Java documentation

## Recovery lookups

The additive order-ID recovery RPC requires `SCOPE_order.reconcile`, returns at most 100 resources, and performs no mutations. This supports discovery after checkout response loss. See [order recovery operations](../docs/order-cleanup.md) for service-identity provisioning and remaining deployment acceptance.


Optimistic-lock retry advice is enabled for reservation mutations. It permits three attempts with bounded backoff; insufficient stock is a business rejection and is not retried. Proxy-level tests check activation separately from the PostgreSQL contention tests still needed for deployment acceptance.
