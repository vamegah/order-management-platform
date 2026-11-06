# Platform verification

The default platform suites exercise provider ingress, outbox draining, outbound
delivery and the payment-to-webhook handoff. They require Java 21 and Docker.
Run from the repository root:

```sh
./mvnw -pl platform-tests -am clean verify
```

The reactor builds both service dependencies. Failsafe runs platform tests with
the repository root as its working directory. Each application loads its own
checked-in configuration, Flyway migrations and PostgreSQL schema. This avoids
duplicate migration versions and ambiguous `application.yml` resources when
both service jars share a test classpath. Hibernate schema validation and
production security remain enabled.

Shared test containers provide PostgreSQL, Kafka and Redis for the lifetime of
the test JVM. Application contexts close after each suite; tests wait for the
webhook source, delivery/retry and DLQ consumer groups to commit all published
records before removing subscriptions or closing subscribers. Subscriber HTTP
executors are shut down. JDBC URLs retain Testcontainers' existing query
parameters when adding a schema.

The outbound suites explicitly import an immediate-retry scheduler. It sends to
the production retry topics with a due timestamp, exercising routing and attempt
identity without waiting through the production minute/day delays. Production
retry timing has separate unit coverage. Real delayed HTTP responses exercise
the configured request timeout and successful retry; no timeout test is an
assertion-free placeholder. Invalid stored URLs are tested without external DNS.
Signature tampering changes bytes that actually appear in the delivered body.

`PaymentToWebhookIT` boots payment and notification as independent application
contexts with separate HTTP ports, persistence units and schemas. Tests create
subscriptions through HTTP, charge/refund through authenticated payment HTTP,
and check committed outbox events against subscriber event references and
persisted successful attempts. A local RSA issuer serves public JWKS; the
production decoders verify signatures and issuer claims. The provider is the
deterministic local `noop` implementation. The single-service synthetic webhook
endpoint test still uses a mock JWT decoder, and repository-created subscriptions
in the other outbound suites deliberately isolate worker behavior.

The current outbound domain payload contains an event reference. These tests
check the original payment payload in the committed outbox and correlate its
event ID with the HTTP delivery. Full payload enrichment and customer-scoped
event matching require separate acceptance; this suite does not claim them.

Canonical producer names and their nine published dotted aliases match the
same subscriptions. For example, `PAYMENT_REFUNDED` reaches a subscription for
`payment.refunded` with that subscribed spelling in the HTTP envelope. A
subscription listing both receives one delivery and prefers the original source
spelling. Delivery IDs continue to depend only on subscription and event IDs.
This preserves the existing source event contract and stored subscriptions.
The equivalent pairs are order placed/shipped/cancelled, inventory reservation
created/released, and payment intent-created/confirmed/failed/refunded. Other
types keep their exact spelling; this is not a blanket renaming rule.

The synthetic delivery endpoint returns HTTP 202 with `deliveryId` and
`status: queued`. `webhook.delivery.timeout-seconds` controls the actual HTTP
request timeout, defaults to ten seconds and must be between one and sixty.
Connect timeout remains ten seconds; the request timeout also bounds connection
establishment when configured lower.

## Packaged container journey

`PaymentToWebhookContainerIT` is an ordinary integration suite included in default
`verify`. It starts its own PostgreSQL,
Kafka and Redis plus two Java 21 containers, copying each freshly packaged
`*-exec.jar` into the appropriate service container. It uses Docker network
aliases, allocated host ports and Testcontainers' host-port forwarding for
JWKS. A standalone JDK subscriber runs inside that Docker network, so the
delivery path does not depend on callback forwarding to the host. It needs no separately built `:test` images, fixed
host ports, Compose binary or authentication-bypass filter. Before publishing,
the fixture waits for stable Kafka groups assigned to every partition of each
source, delivery/retry and DLQ topic; HTTP health alone does not prove that
listeners are ready. Domain topics must have the producer's three partitions.
Notification consumers disable automatic topic creation: otherwise starting
notification before payment can create one-partition domain topics, and payment's
later expansion leaves additional partitions unread until a metadata refresh.
Topic administrators retain responsibility for provisioning. A bounded authenticated read also
loads and validates the issuer's remote JWKS before steady-state invalid-token
checks; those checks retain their ten-second request deadline.

Run this focused journey after packaging its reactor dependencies:

```sh
./mvnw -pl platform-tests -am -Pe2e package \
  failsafe:integration-test failsafe:verify \
  -Dit.test=PaymentToWebhookContainerIT -Dfailsafe.failIfNoSpecifiedTests=false
```

This command selects one container suite; it does not replace full reactor
`verify` or the external full-stack suites in `integration-tests`. The packaged
journey uses signed customer tokens, verifies subscriber HMAC independently,
checks authenticated delivery history and rejects malformed tokens and tampered RSA signatures at both
services. The isolated fixture uses plaintext internal transport and a local
issuer/provider; deployed Keycloak, mTLS, ingress, simulator/Stripe and the full
order lifecycle remain separate acceptance work.

The `packaged-payment-journey` CI job runs this command from a clean tree and
requires a nonempty passing report. The final `quality-gate` requires this job
as well as the existing default verification, coverage, static, dependency and
image jobs. Local execution and GitHub execution are separate evidence.

## Execution evidence

`packaged-all-consumers-verify.log` records a successful focused eight-module
build: all 457 unit/in-process tests and both packaged container cases pass with
zero failures, errors or skips. The complete partition-assignment check passes
with notification starting before payment. Earlier diagnostic runs exposed
incomplete assignments to partition zero, missing callbacks on partitions one
and two, and cold JWKS loading in an invalid-signature check. The fixes address
topic creation/assignment and authenticated startup readiness respectively.

`coverage/packaged-payment-evidence.zip` retains the passing packaged report,
build log and source/build input hashes; the companion JSON also records the
executable jar hashes. This focused run does not establish full-reactor or
external full-stack acceptance. A fresh seventeen-module verification is in
progress. Only checked milestones in `TASKS.md` are accepted. Combined
unit/integration coverage must not replace unit-only PR regression snapshots.
