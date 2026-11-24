# Payment and webhook operation

The deterministic topology uses the production Stripe HTTP adapter, raw-byte
signature verifier, durable receipt inbox, financial state/outbox transactions,
Kafka, notification dispatch and subscriber HTTP transport. The simulator and its
public credentials are fixtures. Production uses Stripe and mounted secrets.

## Tokenized payments and SCA

Create a provider payment method with Stripe.js; send its `pm_...` token as
`paymentMethodId` to `POST /api/v1/payments`. The JWT supplies `customer_id`.
The amount, currency, order, provider and method bind to the idempotency key.
An existing internal gRPC intent can be charged by its owner through
`POST /api/v1/payments/{id}/confirm` with the same tokenized method. Neither
endpoint accepts card numbers. A real-provider intent cannot be confirmed by an
unverified gRPC provider-reference string.

A confirmed charge returns 200, a decline 402, an unresolved provider operation
502, and processing/action-required 202. For action-required, pass the private
`nextAction.clientSecret` to Stripe.js `handleNextAction`; do not log or persist
it in browser storage. The response uses `Cache-Control: no-store`. A signed
provider event finalizes the pending intent. Canonical GET determines whether it
is safe to fulfill; a pending or failed payment cannot authorize fulfillment.
Successful client-side authentication alone does not authorize an order.

Retries reuse `omp-charge-{payment UUID}`. Once a provider reference is known,
confirmation reads that intent instead of creating another. An unknown outcome
older than 20 hours requires reconciliation; it must not issue another POST
past Stripe's minimum 24-hour idempotency retention. Method switching on an
existing intent is rejected. Create a new business attempt after a terminal
failure. Cancellation of a dispatched intent requires a verified provider
outcome; an uncertain response retains pending state and blocks new dispatch.

## Durable inbound receipt

`POST /api/v1/payments/webhooks/stripe` reads the original UTF-8 bytes and verifies
`Stripe-Signature` before inserting a unique provider/event identity and SHA-256
body digest. Its 200 response means the receipt committed, not that the financial
change finished. Processing runs asynchronously in short row-locked transactions;
financial changes and their outbox events commit together. Non-financial event
types are durably ignored. A fully correlated Stripe capture can supersede an
older failure notification for the same bound provider intent; it emits exactly
one confirmation, preserving any order cancellation/refund fences. A cancelled
order waits for its inventory compensation fence and then queues one durable
refund, even if an earlier cleanup saw a failed payment. Keep refund recovery
enabled (`PAYMENT_REFUND_RECOVERY_ENABLED=true`) to dispatch that intent. Unverified
client calls cannot reopen a failed payment. Matching duplicates
receive 200 with `duplicate`; identity reuse with different bytes receives 409.
Temporary verification/receipt failures receive 503 so the provider retries.

The body limit is 256 KiB. Signatures have a five-minute timestamp tolerance,
constant-time HMAC comparison and bounded header parsing. Configure
`STRIPE_API_KEY_FILE` and `STRIPE_WEBHOOK_SECRET_FILE` as private mounted files.
For inbound rotation, mount the prior secret through
`STRIPE_PREVIOUS_WEBHOOK_SECRET_FILE` and set the absolute UTC cutoff
`STRIPE_PREVIOUS_WEBHOOK_SECRET_VALID_UNTIL`. Replace projected files atomically;
reads observe rotations without logging credentials. Remove the previous mount
when the overlap ends. The development noop provider uses timestamped
`X-Noop-Signature: t=...,v1=...`; its public development key is disabled in
production profiles. An old shared-secret literal is not a valid signature.

Where a trustworthy direct peer is available, configure the comma-separated
`payment.webhook.allowed-networks` CIDRs. The filter uses the socket peer and
ignores X-Forwarded-For; forwarded-header rewriting is disabled in payment-service.
Behind a reverse proxy, apply the provider IP allowlist at the first public hop
and restrict direct backend access. Do not put Stripe addresses in a backend
filter that only sees the proxy. Maintain the edge list from Stripe's official
[webhook IP feed](https://stripe.com/files/ips/ips_webhooks.json); IP policy always
supplements signature verification. No untrusted request header grants access.

The inbox retains minimal correlation fields, not billing details or raw bodies.
Metrics expose pending/attention counts, oldest age and redacted outcomes.
After twenty processing failures an event requires attention. Administrator-only
`GET /api/v1/payments/webhook-events` lists redacted unresolved work;
`POST /api/v1/payments/webhook-events/{id}/replay` requires an investigation
reason and records actor/reason in the replay audit. Replaying does not bypass
correlation checks. Completed payloads expire after 90 days; financial identities,
digests, unresolved work and replay audit records remain. Use
[refund reconciliation](refund-reconciliation.md) for missing legacy references;
a verified provider GET may establish a missing legacy refund key, never a new
refund POST based on an operator assertion.

## Outbound subscriber contract and security

Production targets require HTTPS, no userinfo or fragment, and public IPv4/IPv6
addresses. Registration, dispatch and the connection's DNS resolver all apply
the same policy. A mixed public/private DNS result is rejected. The connection
uses the validated address while retaining TLS SNI and certificate hostname
verification. Redirects, automatic HTTP retries, ambient proxies and cookies
are disabled. Private test hosts require an explicit development/test profile
and exact-host allowlist; production refuses that override.

The [delivery v1 JSON Schema](../contracts/webhooks/delivery-v1.schema.json)
and frozen baseline are checked alongside event contracts. The original event
payload and occurredAt survive delayed delivery. Consumers accept additive fields.
A delivery keeps its `deliveryId` through retries/replay; `attempt` changes.
HTTP delivery is at least once. Subscribers must deduplicate business actions
using the delivery ID and durably accept before replying with 2xx.

Verify HMAC-SHA256 over `X-Timestamp + "." + rawBody`, enforce a five-minute
receive window, and compare in constant time. `X-Webhook-Signatures` contains
`v1=<hex>` values for current and, during rotation, previous keys. The older
`X-Signature-256: sha256=<hex>` header remains current-key compatible. Rotation
returns the new secret only to the authorized owner and preserves the previous
key for 24 hours. A second rotation during that overlap returns 409. Distribute
the new secret before retiring the old receiver key. Headers also include
`X-Webhook-Delivery-Id`, `X-Webhook-Event` and `X-Webhook-Key-Version`.

Workers use a 32-connection pool, four connections per route, a two-second pool
wait, three-second connect timeout, and a ten-second total request deadline.
Response audit text is limited to 4096 bytes. Retryable responses are transport
errors, 408, 429, 401/403 and 5xx. TLS/destination/redirect and other 4xx failures
are permanent. Six attempts use 1m, 5m, 30m, 2h and 24h backoff with jitter;
Retry-After delta or HTTP-date is a lower bound capped at 24h. Ten consecutive
failures disable a subscription. Inspect and repair before enabling it.

Administrator-only DLQ replay records actor and payload hash transactionally
with the durable dispatch. Concurrent replay is fenced; repeated replay returns
409. A replay that fails again returns to the unresolved list. Successful
receipts remain as dedup tombstones. After 90 days, bounded cleanup removes
completed attempts, successful replay payloads and unused source payloads;
unresolved DLQs and audit/receipt identities remain. Deliberate administrator
DELETE dismisses a DLQ entry; use it only after reconciling the business event.

## Reproduce deterministic acceptance

Use a disposable project and the public development fixture environment:

```sh
export COMPOSE_PROJECT_NAME=omp-payments-acceptance-local001
bash scripts/ci-setup.sh
python -m pip install -r scripts/contracts-requirements.lock
docker compose --env-file .env.example -f docker-compose.yml -f docker-compose.e2e.yml -f docker-compose.payments-e2e.yml build payment-service notification-service payment-provider-simulator
docker compose --env-file .env.example -f docker-compose.yml -f docker-compose.e2e.yml -f docker-compose.payments-e2e.yml up -d --no-build --wait --wait-timeout 600 payment-service notification-service payment-provider-simulator
python scripts/verify_payment_webhooks.py --project "$COMPOSE_PROJECT_NAME"
docker compose --env-file .env.example -f docker-compose.yml -f docker-compose.e2e.yml -f docker-compose.payments-e2e.yml down --volumes --remove-orphans
```

The [predeclared workload/SLO profile](webhook-delivery-slos.json) is committed
before measurements. Seven required phases cover provider lifecycle/security,
500 callbacks at 50/s, 200 charges at 10/s, signing rotation, slow/lost
acknowledgments, 200 failed-subscriber replays, provider/notification restarts,
and database/broker interruptions. Reports include source revision/input hash,
runtime image IDs/hardware, acknowledgment/delivery percentiles, backlog age,
drain rate and recovery times. JUnit and safe measurements are retained in the
required `payments-e2e` CI job. Smaller local hosts are diagnostic; acceptance
uses the declared Linux reference host. An explicit image-tag variable supports
isolated local artifacts without replacing development images.

The durable notification drainer claims at most 100 due tasks per transaction,
works for at most one second between transport calls, and stops on the first
transport failure. Unattempted rows remain pending. Each charge can create three
dispatch tasks, so capacity must account for fan-out as well as charge rate.

The provider request uses [Stripe dynamic payment methods](https://docs.stripe.com/payments/payment-methods/dynamic-payment-methods)
with redirects disabled and a client-supplied token. SDK SCA remains supported;
the backend never accepts raw card details.

Real Stripe test-mode acceptance is separate from deterministic CI. Use an
explicitly authorized test sandbox, store API/listener keys in private mounted
files, retain TLS verification and verify `livemode=false` through the Stripe API
before a charge. `scripts/verify_stripe_test_mode.py` requires the CLI test-profile
configuration, matching API-key file and an existing uncaptured test intent for
that guard. A Stripe CLI listener forwards signed callbacks to the isolated
payment service. It records test-token capture/retry/decline, real SDK SCA pending
state, and asynchronous refunds without writing credentials or client secrets to
reports. Deterministic CI additionally completes both SCA outcomes. Never point
the simulator or this load harness at production or supply real card details.
