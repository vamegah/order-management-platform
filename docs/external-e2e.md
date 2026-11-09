# External order and real-time acceptance

These suites call a separately deployed development stack through Traefik and
the published gRPC ports. They do not start infrastructure. Use a dedicated,
fresh test environment: the lifecycle case ships one seeded item, and the
WebSocket case requires no other active subscriptions on its notification
replica. Never run the development realm or these mutations against production.

Use Docker Compose 2.24.4 or newer; the overlay uses `!override` to replace
image-specific Kafka configuration and volumes. It selects Apache Kafka 3.8.1
because the base `bitnami/kafka:3.8` tag is no longer published. Its separate
volume avoids interpreting existing Bitnami data as Apache image data. This
single-broker acceptance topology does not migrate the production Kafka cluster.

From the repository root, after configuring `.env` from `.env.example`:

```sh
bash scripts/ci-setup.sh
docker compose -f docker-compose.yml -f docker-compose.e2e.yml \
  up -d --build --wait --wait-timeout 600 traefik \
  product-service customer-service order-service \
  inventory-service payment-service notification-service
./mvnw -Pe2e clean verify
python scripts/check_verification.py --include-e2e
```

The final command rejects missing, skipped, failed and errored suites. The full
reactor command also runs default container tests. For just the externally
deployed journeys, use `./mvnw -pl integration-tests -am -Pe2e verify`; this focused
command does not replace complete reactor acceptance.

The Java test runner also needs Python 3.12 or newer on its path. Set `E2E_PYTHON`
to its executable path when it is not named `python`. `RecoveryTransportIT` runs
the checked-in token projector as a child process from the repository root;
custom Linux caller images must include Python as well as Java. The dedicated
stack must import the current development realm, including `order-recovery`.

The opt-in Compose override binds inventory/payment gRPC to loopback ports 9091
and 9093, retains their mTLS configuration, and exposes notification management
on loopback port 18085. It selects the development `noop` payment provider for
deterministic refunds. Certificates generated for order-service authenticate the
test caller. Server certificate authority names remain `inventory-service` and
`payment-service` even when connecting through localhost port mappings.

On a fresh database the overlay also initializes the six service-role passwords
from the configured development environment. The base schema script deliberately
creates passwordless roles; without this step the services cannot connect.
The additional init SQL is mounted only by the e2e overlay, keeps passwords out
of command arguments, and disables statement logging for that session. Existing
volumes do not rerun initialization. The overlay explicitly binds all event
producers to the Compose Kafka broker and uses one replica for webhook topics
on that single broker.

Order-service uses DNS-resolving gRPC targets, TLS and explicit client-certificate
authentication for inventory/payment. Its startup waits for both services to be
healthy. Supplying certificate paths alone does not enable the client identity.

The overlay selects a Traefik version compatible with Docker 29, enables its
health endpoint and attaches routed REST services to the ingress network. Its
development startup allowance retains the actual health commands while allowing
several JVMs to initialize on a resource-limited engine. All six core runtime images use Jammy: the deployed Alpine payment image crashed
in native TLS, and notification could not load Kafka's native Snappy library. Runtime-image acceptance must include startup and transport calls,
in addition to building the image and extracting its JAR layers.

Certificate setup validates and preserves existing identities. It rejects
expired, incomplete, mismatched or foreign-CA service certificates instead of
silently replacing keys. On Linux, the mounted service keys must be readable by
container UID/GID 1000; CI keeps the host test user as owner and grants GID 1000
read permission on those leaf keys. The CA private key remains owner-only and
is never mounted into applications. Host clients also need the development
`*.localhost` names to resolve to loopback; CI supplies explicit host entries.

`GrpcClientFactory` uses TLS by default and fails on missing identity/trust files.
`GRPC_CA_CERT_FILE`, `GRPC_CLIENT_CERT_FILE` and `GRPC_CLIENT_KEY_FILE` override
the root-relative certificate paths. `GRPC_TLS_ENABLED=false` is an explicit
option only for an isolated fixture configured to accept plaintext. Every unary
client receives a ten-second deadline; suite cleanup terminates and resets the
channels so subsequent suites can reconnect.

The imported development realm now maps Alice's administrator-provisioned
`customer_id` attribute to the seeded customer UUID. An existing realm is not
updated by a normal import: apply the mapper/attribute administratively or use a
fresh dedicated realm. Do not delete unrelated volumes to refresh it. Tokens
refresh before their issuer-provided expiry. Optional `E2E_ALICE_USERNAME`,
`E2E_ALICE_PASSWORD`, `E2E_ADMIN_USERNAME`, `E2E_ADMIN_PASSWORD` and `E2E_CLIENT_ID`
override development credentials without placing them in command arguments.

The seeded development logins are `alice` / `alice-dev-password` and `admin` /
`admin-dev-password`. Both satisfy the realm's twelve-character minimum. The
import defines its basic/role/profile/email/origin scopes explicitly, including the
subject mapper and
`realm_access.roles` mapper required by service authorization. Keycloak 25 moves
subject mapping into `basic`; omitting that scope issues subjectless access tokens
that STOMP correctly rejects. These credentials
and the password grant belong only to this development realm.

The default gRPC hosts are IPv4 loopback (`127.0.0.1`), matching the published
port bindings. A host antivirus HTTPS interceptor can substitute its own server
certificate even on loopback; the original-CA client correctly rejects that
identity. Use an isolated Linux runner on the Compose application/edge networks
when the host intercepts TLS. Keep the original CA, client key and certificate
verification; do not add the substituted identity to the trust store. REST and
WebSocket calls must still traverse Traefik.

Endpoint overrides are `KEYCLOAK_URL`, `ORDER_SERVICE_URL`,
`INVENTORY_GRPC_HOST`, `INVENTORY_GRPC_PORT`, `PAYMENT_GRPC_HOST`,
`PAYMENT_GRPC_PORT`, `NOTIFICATION_WS_URL` and `NOTIFICATION_METRICS_URL`.

Each case creates its own order using the existing customer/catalog/stock seeds.
Order responses now expose the committed `paymentId` and each line's
`reservationId`; historical rows may omit those fields. The tests read the
resources by these references rather than creating replacements to discover IDs.

The cases cover draft/placed/confirmed/fulfilling/shipped/delivered transitions,
rejection before payment, idempotent confirmation/shipment/cancellation, committed
inventory, reservation release, payment cancellation and partial/full refunds.
The orphan-admission suite uses real issuer tokens through ingress to verify
administrator-only access, bounded reasons, malformed UUID problems, the verified
operator subject and preservation of the first audit on repeats. Its test-owned
order proves admission cannot reclassify an accepted aggregate or change its
inventory/payment resources; that order is cancelled afterward. Admission-only
UUIDs have no remote resources and disappear with the isolated test database.

Five recovery-identity cases project real client-credentials JWTs to private
temporary files, then call both scoped discovery RPCs over the existing mTLS
client. They require TLS even if another fixture supports plaintext. The cases
check exact references on a test-owned order, renewed projection acceptance on
existing channels, rejection after changing a valid token's signature,
ordinary administrator/customer scope denial, and denial of administrator orphan
admission to the workload identity. The owned order is cancelled afterward.
Secret and token files stay in the runner's temporary directory; child-process
output is discarded and diagnostics contain no bearer values. These cases do
not enable a recovery worker or exercise process termination, lease recovery,
invalid client certificates or accepted-order expiry coordination.

The WebSocket case first waits for the public SockJS ingress route after container
replacement, then waits for the authenticated subscription gauge, triggers a
new order event, verifies its identity, and checks unsubscribe cleanup. It uses
JSON object conversion and bounded polling rather than a fixed propagation sleep.
This gauge is local to a notification replica; multi-replica readiness and
reconnect/recovery remain separate acceptance work.

These are executable acceptance specifications. Their deployment/execution
results must be recorded in the implementation report before marking full-stack
acceptance complete; successful compilation alone does not satisfy that gate.

## Recovery compensation transport specifications

Two additional `RecoveryTransportIT` cases use a fresh order UUID with no order
aggregate and the real projected workload JWT over mTLS. Fixture setup uses an
administrator to reserve one seeded item and create a pending development payment.
Returned order/resource identities, customer, stock coordinates and amount are
validated before any mutation or cleanup action uses them.

The first case discovers its exact references, releases the reservation and
cancels the payment twice, then reads both terminal states. The second confirms
its own payment, requests its remaining refund without an amount, and repeats
the same idempotency key. It requires one refund identity, completed status, the
full USD 12.34 amount and unchanged persisted payment/refund history on replay.
Cleanup uses only verified fixture resource IDs. The development `noop` provider
supplies deterministic refunds; these cases do not contact a live processor.

Run the seven-case transport suite against the dedicated stack:

```sh
./mvnw -pl integration-tests -am -Pe2e verify \
  -Dit.test=RecoveryTransportIT -Dfailsafe.failIfNoSpecifiedTests=false
```

Formatting and compilation pass locally. The two new deployed cases have not
executed locally; publication and full CI execution remain required. The complete
external job must execute 23 service/client cases, including all seven transport
cases, while the default reactor remains at 1,193 cases. Exact-source compile
evidence is retained in `coverage/recovery-compensation-transport-local-evidence.zip`
and JSON. These specifications issue RPCs directly; scheduled worker deployment,
checkout failure injection, downstream restart and production provider acceptance
remain separate work.

## Scheduled recovery acceptance

`ScheduledRecoveryIT` exercises the actual scheduled worker inside the packaged
Boot application with a supervised development token projector and real JWT/mTLS
inventory/payment containers. It runs after ordinary e2e and client cases so
background reconciliation and container stops cannot affect their assumptions.
The normal `e2e` profile excludes its `recovery-e2e` tag; the `recovery-e2e`
profile runs only that tag. The final CI evidence check requires both groups.

Provision the private projection/credential paths using the
[supervised identity procedure](order-recovery-identity.md#supervised-development-projection).
Use a dedicated project whose name matches `omp-recovery-acceptance-` followed by
twelve lowercase hexadecimal digits. CI instead uses its unique
`omp-e2e-RUN_ID-ATTEMPT` project. After the ordinary suites pass, enable the
projector and acceptance overlays for that same isolated stack:

```sh
docker compose --env-file .env.example -f docker-compose.yml -f docker-compose.e2e.yml \
  -f docker-compose.recovery.yml -f docker-compose.recovery-projector.yml \
  -f docker-compose.recovery-acceptance.yml \
  up -d --no-build --wait --wait-timeout 300 order-service
E2E_RECOVERY_RESTARTS=true ./mvnw -pl integration-tests -am -Precovery-e2e verify
python scripts/check_verification.py --root integration-tests \
  --include-e2e --include-recovery-e2e
```

Set `COMPOSE_PROJECT_NAME` for both Compose and Maven. Container controls require
the explicit restart opt-in, verify project/service labels before each operation,
and accept only order, inventory, payment and the projector. They do not remove
containers, databases or volumes. `E2E_DOCKER` selects a local Docker executable;
an optional `E2E_RECOVERY_COMPOSE_OVERRIDE` must be an existing file under
`.scratch` and applies to the fixture's one-off projection replacement too.

The first case waits for malformed-projection failures, admits only its verified
orphan resources, restarts the application and both downstream services, and
requires automatic release and full refund. A simultaneous accepted order keeps
its resources. After that recovery, the projector's own refresh must replace its
private token; a second orphan then recovers on the existing application/channels. Terminal
history remains stable. The second case observes its own failed discovery during
an inventory outage, restarts order-service, proves the resources remain unchanged
during persisted backoff and waits for the real five-minute retry to cancel and
release them. No SQL timestamps or direct worker ticks shorten this interval.

The fixture waits for the public order health route before creating resources
and after application restarts. It restores every controlled service on failure
and retains any primary assertion failure if cleanup also fails.

The private projection/credential checks execute inside the Linux projector:
UID 1000, directory mode 0700, token mode 0600, credential mode 0400 and matching
ownership. Only a token digest leaves that process. The application uses its
normal read-only directory mount. Windows local acceptance may supply private
Linux Docker volumes through the scoped override instead of relying on POSIX
permissions on a Windows bind mount.

Compilation/configuration evidence and executed deployment results are recorded
separately in the implementation report. Complete CI must execute both new
cases, all seven recovery transport cases and the other ordinary/deployed client
cases (25 deployed cases in total). The default reactor retains 1,196 cases.
This is development-provider recovery acceptance; full checkout failure injection,
accepted-order expiry/fulfillment compensation and production identity/provider
acceptance remain open.
