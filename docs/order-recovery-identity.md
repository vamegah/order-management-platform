# Projecting the order recovery workload identity

Order recovery requires its own short-lived identity. The development realm
defines the confidential `order-recovery` service account, with client
credentials enabled, browser/password grants disabled and full-scope access
disabled. Its only default scopes are `basic` and `order.reconcile`; it has no
customer claim or administrator role. The recovery scope includes inventory and
payment audiences. Ordinary development clients are not assigned this scope.

The client-credentials setup follows the [Keycloak service account contract](https://www.keycloak.org/docs/25.0.6/server_admin/).
The checked-in client secret is a public development fixture, like the realm's
other development credentials. Provision a separate production client and secret.
An existing realm is not updated by startup import; add the client and scope
administratively from the reviewed definitions or use a fresh dedicated test
realm. Do not overwrite a live realm or delete its data to apply this change.

## Token projection

`scripts/project_service_token.py` exchanges a credential read from a bounded
local secret file. It requests only `order.reconcile`, rejects redirects and
uses HTTPS with ordinary certificate verification. Plain HTTP requires the
explicit development setting shown below. Responses, credentials and JWTs are
never written to logs. Metadata checks require the expected issuer, authorized
client, scope, audiences and remaining lifetime; inventory/payment remain the
authority for cryptographic JWT verification and authorization.

The projector writes a private temporary file, flushes it and atomically replaces
the token. A failed refresh leaves the last projection intact. It reloads the
client credential for every exchange, refreshes at most sixty seconds apart and
retries failures after five seconds. Once an old token expires, downstream
services reject it; failures do not grant continued access. Run the projector
under a process supervisor and monitor its refresh/failure messages.

## Dedicated development environment

Use Python 3.12 or newer and an isolated development stack with the updated realm
imported. On Linux run the projector as the application's UID (1000). The private
directory and mode-0600 token must be readable by that UID in the container;
configure that service identity explicitly on hosts with a different user UID.
Keep these runtime files outside source control. From the repository root:

```sh
umask 077
mkdir -p .scratch/order-recovery/projection
printf '%s' 'dev-only-order-recovery-secret' > .scratch/order-recovery/client-secret
export ORDER_RECOVERY_TOKEN_DIRECTORY="$(pwd)/.scratch/order-recovery/projection"
export SERVICE_TOKEN_OUTPUT="$ORDER_RECOVERY_TOKEN_DIRECTORY/access.jwt"
export SERVICE_TOKEN_CLIENT_SECRET_FILE="$(pwd)/.scratch/order-recovery/client-secret"
export SERVICE_TOKEN_CLIENT_ID=order-recovery
export SERVICE_TOKEN_ISSUER=http://keycloak.localhost/realms/omp
export SERVICE_TOKEN_URL="$SERVICE_TOKEN_ISSUER/protocol/openid-connect/token"
export SERVICE_TOKEN_ALLOW_HTTP=development
python scripts/project_service_token.py --once
python scripts/project_service_token.py
```

Keep the refresh process running. In another terminal with the same
`ORDER_RECOVERY_TOKEN_DIRECTORY`, opt in for the same dedicated Compose project:

```sh
docker compose -p omp-recovery-dev -f docker-compose.yml \
  -f docker-compose.e2e.yml -f docker-compose.recovery.yml up -d order-service
```

The overlay mounts the **directory** read-only, so replacement tokens become
visible on subsequent reads. A bind mount of one token file can retain its old
inode after replacement. The directory must already exist; Compose will not
create it. The overlay enables checkout recovery only; it preserves existing
mTLS configuration and leaves inventory expiry and fulfillment workers unchanged.
Both base and e2e configurations retain recovery's disabled default.

`python scripts/project_service_token.py --check` exits successfully only for a
bounded, well-formed projection with matching metadata and at least thirty
seconds remaining. This is a projection health check, not proof of downstream
acceptance. Monitor the worker's `order.recovery.scans`, `order.recovery.failures`,
`order.recovery.worker.failures` and cleanup counters as described in
[order cleanup](order-cleanup.md). Tokens must remain valid for the worker's RPC
work; batch sizes, deadlines and actual recovery duration need deployed testing.

To disable recovery, recreate order-service using the same project and the base
plus e2e files without the recovery overlay, then stop its projector. Existing
durable journal and cleanup tasks remain available for a later authorized retry.

## Production and remaining acceptance

### Supervised development projection

`docker-compose.recovery-projector.yml` adds a supervised, non-root projector
beside the existing recovery overlay. It uses UID/GID 1000, a read-only root
filesystem, dropped capabilities, a bounded container and a projection health
check. Order-service waits for the initial healthy projection. Its directory is
writable by the projector and mounted read-only by order-service, preserving
atomic replacement. Traefik's isolated edge-network alias resolves the public
development issuer from the projector container; mTLS settings stay intact.

On a Linux development host, provision a private credential from the development
realm under the application identity. `DEVELOPMENT_RECOVERY_CREDENTIAL` below is
the path to an existing private credential file, not its secret value:

```sh
sudo install -d -m 0700 -o 1000 -g 1000 .scratch/supervised-recovery
sudo install -d -m 0700 -o 1000 -g 1000 .scratch/supervised-recovery/projection
sudo install -m 0400 -o 1000 -g 1000 "$DEVELOPMENT_RECOVERY_CREDENTIAL" \
  .scratch/supervised-recovery/client-secret
export ORDER_RECOVERY_TOKEN_DIRECTORY="$(pwd)/.scratch/supervised-recovery/projection"
export ORDER_RECOVERY_CLIENT_SECRET_FILE="$(pwd)/.scratch/supervised-recovery/client-secret"
docker compose -p omp-recovery-dev --env-file .env.example \
  -f docker-compose.yml -f docker-compose.e2e.yml -f docker-compose.recovery.yml \
  -f docker-compose.recovery-projector.yml up -d --wait order-service
```

The credential is a file mount, so changing its host inode requires recreating
the projector. Token rotation uses the directory mount and needs no application
recreation. A refresh failure preserves the last token; expiry still causes
downstream rejection. Supervision restarts an exited projector; an unhealthy
running projector remains visible for intervention rather than granting access.
This overlay deliberately uses the development issuer over HTTP. Production
requires its own HTTPS issuer, external credential provisioning and rotation
acceptance described below.

The separate `docker-compose.recovery-acceptance.yml` shortens only the test
scheduler interval and publishes authenticated order metrics on IPv4 loopback.
`ScheduledRecoveryIT`, selected with `-Precovery-e2e`, controls only explicitly
opted-in isolated containers and exercises private projection renewal, worker and
downstream restarts, confirmed-payment refund, pending-payment cancellation and
the persisted five-minute discovery retry. It makes no direct worker call and
edits no database clock/eligibility fields. See [scheduled acceptance](external-e2e.md#scheduled-recovery-acceptance).
Executed results belong in the implementation report; these definitions alone
do not close lifecycle or production acceptance.

### Issuer and transport verification

Run `python scripts/verify_recovery_identity.py` to repeat the isolated issuer
acceptance with Docker available. It creates a fresh Keycloak 25 development
container, imports this realm and checks scoped issuance, token rotation,
credential rejection, ordinary-client scope denial and the published signing-key
identity. It removes only its own labelled container and temporary credentials.
Its JSON report contains public metadata and source hashes, never tokens or
credentials. CI requires this job alongside the existing application gates.

`RecoveryTransportIT` adds separate deployed acceptance specifications to the
external e2e profile. It invokes this projector with the committed development
client in private temporary files, then uses its issued JWT with the original
mTLS client identity against inventory and payment. Its five cases cover owned
resource discovery, projection renewal on existing channels, invalid signatures,
ordinary-user scope denial and workload denial at the administrator admission
endpoint. The runner requires Python 3.12 or newer (`E2E_PYTHON` can select its
executable). See [external e2e](external-e2e.md) for the fresh-stack command and
certificate setup. Compilation alone does not establish deployed acceptance;
execution results must be recorded in the implementation report.

Use a trusted HTTPS token endpoint and issuer, a dedicated client credential from
the deployment secret manager and a supervised projector running as the
application identity. Do not copy development secrets or saved user JWTs into
production. Keep the projection directory private, mount it read-only into the
application and verify signature/issuer/audience policy in downstream services.
This scope permits cross-order discovery and compensation and should be assigned
only to the recovery workload.

The local issuer fixture and HTTP/file regressions do not establish deployed
inventory/payment authorization, process-crash recovery, accepted-order expiry
coordination or production secret rotation. Those acceptance items remain open
in [TASKS.md](../TASKS.md). No default worker is enabled by this change.
