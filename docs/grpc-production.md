# Production gRPC transport and streaming

Section 3 uses the generated inventory and payment contracts with production-profile
services. Inventory provides unary RPCs, client-streaming `BulkReserve`,
server-streaming `StreamStockUpdates`, and bidirectional `CheckAvailabilityStream`.
Payment provides unary RPCs and server-streaming `StreamPaymentEvents`.

The required `grpc-e2e` CI job discovers every method from the generated service
descriptors. Each must have executed positive, domain-error, missing/invalid JWT,
transport-deadline and cancellation cases. The suite writes
`integration-tests/target/grpc-acceptance-matrix.json`; its final guard fails when
any published RPC has a missing scenario. JUnit evidence must contain all 82
cases, without failures, errors or skips. Contract evolution still compares the
published v1 descriptor and the exact pull-request base.

## Flow control and recovery

`BulkReserve` accepts at most 100 items of 4096 serialized bytes each (including
unknown fields), validates one canonical order UUID and
commits each item through the transactional unary handler. Cancel before
half-close discards the accumulated input. Failure after a committed item permits
idempotent replay; it does not roll back earlier committed items.

The bidirectional availability RPC processes ordered read-only requests. Each
session has one inbound request credit and at most one pending response. It
replenishes credit after delivering that response; half-close drains the final
response before completion. Invalid requests terminate with `INVALID_ARGUMENT`;
missing stock returns `NOT_FOUND`. Clients should set a deadline and honor their
request observer's readiness.

Stock and payment streams emit immutable snapshots only after the originating
transaction commits. Rollback produces no notification. Writes to each subscriber
are serialized. Slow consumers may miss intermediate notifications; applications
must periodically reconcile via `GetStockLevel`/`GetPayment`. Neither stream is a
durable event log, and there is no replay cursor.

The supported topology remains one inventory replica and one payment replica,
with stop-first updates and rollbacks. On replacement, reconnect with a fresh JWT,
resubscribe and reconcile canonical state. The deployed suite replaces both actual
containers, requires different container IDs, and verifies subsequent delivery.
See [stream topology](grpc-stream-topology.md). Enforce the resolved topology and
transport after every production overlay:

```sh
docker compose --env-file .env.prod -f docker-compose.yml \
  -f docker-compose.prod.yml config --format json \
  | python scripts/check_grpc_transport.py
```

## Certificate projection and renewal

Production forces rotating mTLS and requires a client certificate on both servers.
The workload consumes read-only **directories**:

- `/etc/tls/identity`: its own `tls.crt` and unencrypted PKCS8 `tls.key`.
- `/etc/tls/trust`: the public `ca.crt` trust bundle.

The issuer's CA private key is never mounted into an application. Keep signing
material on the protected issuer host; use the existing development setup only
for disposable environments. Existing PEM deployments must switch their file
bind mounts to the directory mounts before enabling renewal. Preserve the key
when converting an older PKCS1 file to PKCS8.

The shared gRPC module polls every ten seconds (`GRPC_CERTIFICATE_RELOAD_INTERVAL`).
It validates key correspondence, chain trust, validity and both client/server EKUs
before installing credentials. A missing, invalid or partially replaced projection
retains the last validated credentials; invalid initial material fails startup.
Existing HTTP/2 connections and subscriptions stay open. Fresh handshakes use the
new leaf and trust managers. Expired credentials still fail TLS validation.

The production overlay reads `/etc/omp/tls` on its host. The issuer service must
renew that same directory. Grant application GID 1000 read access to each leaf key
(mode 0640), keep the CA key private to the issuer (mode 0600), and preserve directory
traversal for the application. The acceptance workflow verifies these permissions.

Install `infrastructure/tls/omp-grpc-renewal.service` on the protected issuer host
with the repository scripts available under `/opt/omp/scripts`, then enable it with
`systemctl enable --now omp-grpc-renewal`. Its read/write access is restricted to
`/etc/omp/tls` and it needs no network access. Its equivalent command is:

```sh
python3 scripts/rotate_grpc_certificates.py --tls-dir /etc/omp/tls --watch \
  --interval-seconds 3600 --renew-before-days 7 --lifetime-days 30
```

The signer serializes renewals, validates all existing identities before
publication, generates new keys and signed 30-day leaves, validates each result,
and atomically replaces the key and certificate while preserving consumer file
ownership. It publishes only public trust material. The brief unmatched-pair
interval is rejected by the watcher until the complete pair is available.
A single-run `--force` supports acceptance. Supervise/restart the watcher if an
issuer operation fails; do not wait until a service certificate expires.

CA replacement requires an overlap: distribute an old+new public trust bundle
with `--trust-bundle`, allow every consumer to reload it, then install the new
issuer CA/key and renew leaves. Remove an old root only after all peers and
external consumers use new leaves. The deployed acceptance performs all three
overlap phases, requires every workload to reload each projection, keeps calls and
streams active, then proves that still-valid old-root clients are rejected.
Root-key provisioning remains an issuer-host
operation. Automatic leaf renewal never invents or replaces a CA.

The acceptance deployment continuously performs calls while renewing all three
workload identities. It requires new peer serial numbers on fresh connections,
keeps existing stock/payment streams active, checks unchanged container start
times, and completes checkout through the real order-service clients. Negative
cases reject plaintext, absent/untrusted/expired client certificates, incorrect
EKUs, untrusted server roots and incorrect hostnames, even when a valid JWT is supplied.

## Run deployed acceptance

Use only a disposable project named `omp-grpc-acceptance-<12 hex digits>`.
Prepare its issuer and negative fixtures with
`scripts/setup-grpc-acceptance.sh --output <private directory>`, and export
`E2E_GRPC_ISSUER_DIRECTORY`, `COMPOSE_PROJECT_NAME`, `E2E_GRPC_CONTROLS=true`,
`GRPC_CA_CERT_FILE`, `GRPC_CLIENT_CERT_FILE` and `GRPC_CLIENT_KEY_FILE`.
The workflow contains the complete setup and key permissions.

```sh
docker compose --env-file .env.example -f docker-compose.yml \
  -f docker-compose.e2e.yml -f docker-compose.grpc-e2e.yml \
  up -d --build --wait traefik kafka product-service customer-service \
  order-service inventory-service payment-service
bash mvnw -pl integration-tests -am -Pgrpc-e2e verify
python scripts/check_junit.py \
  integration-tests/target/failsafe-reports/TEST-com.example.omp.integration.GrpcProductionIT.xml \
  --minimum-tests 82
```

This suite pauses/recreates only labeled containers belonging to that explicitly
opted-in project. It never controls a production deployment. Remove the disposable
project and its volumes after collecting reports.
