# Payment-provider simulator

Opt-in, bounded dev/test provider fixture using the production Stripe adapter.
The isolated [payment topology](../docker-compose.payments-e2e.yml) deploys it on
8090 with health/metrics on 8091, a persistent `/data/provider` volume, and signed
callbacks to payment-service's actual REST webhook endpoint. Production profiles
refuse startup. The base stack enables it only with `--profile payment-tests`.

The form-encoded API supports `POST/GET /v1/payment_intents`, intent cancellation,
and `POST/GET /v1/refunds`. Create requests require Idempotency-Key plus UUID
correlation metadata. Repeated identical requests preserve IDs; conflicting
reuse returns 409. Atomic file replacement persists resources, callback leases
and controls across process/container restart. One replica owns each ledger
volume, which holds at most 5,000 resources. Capacity exhaustion fails explicitly;
remove only a disposable project's volume to reset it.

Use public fixture Bearer key `sk_test_omp_simulator`. Tokens are `pm_success`,
`pm_decline`, `pm_action`, `pm_processing`, `pm_fail_late` and `pm_timeout`.
`POST /__test/payment_intents/{id}/complete` with `{ "success": true }` or false
completes SCA. Authenticated `PUT /__test/controls` accepts delayMs (0-30000),
responseDelayMs (0-5000), duplicateCallbacks (0-3), reverseCallbacks,
dropCallbacks and refundOutcome (succeeded/failed). `GET /__test/stats` exposes
safe resource counts. Refunds reserve the captured balance until completion.

Callbacks have stable provider event identities and fresh Stripe-compatible HMAC
signatures. Four bounded workers deliver or retry after transport/non-2xx failure;
leases survive restart. The legacy `/api/simulate` endpoints remain development
helpers; use the `/v1` fixture for correlated end-to-end acceptance. The simulator
never contacts Stripe or accepts raw card data.

Build with `docker build -f payment-provider-simulator/Dockerfile .` and run the
seven-phase [payment/webhook acceptance](../docs/payment-webhooks.md). The runbook
covers exact commands, SLOs, source-bound evidence, signing rotation, recovery,
and the separate explicitly authorized real Stripe test sandbox check.
