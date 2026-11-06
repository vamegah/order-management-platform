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
The WebSocket case first waits for the public SockJS ingress route after container
replacement, then waits for the authenticated subscription gauge, triggers a
new order event, verifies its identity, and checks unsubscribe cleanup. It uses
JSON object conversion and bounded polling rather than a fixed propagation sleep.
This gauge is local to a notification replica; multi-replica readiness and
reconnect/recovery remain separate acceptance work.

These are executable acceptance specifications. Their deployment/execution
results must be recorded in the implementation report before marking full-stack
acceptance complete; successful compilation alone does not satisfy that gate.
