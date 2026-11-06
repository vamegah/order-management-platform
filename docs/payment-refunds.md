# Provider-backed refunds and order cleanup

REST `POST /api/v1/payments/{paymentId}/refunds` and gRPC `RefundPayment` now use
the same provider-backed workflow. The old gRPC path completed a local refund
without contacting the provider; it has been removed. Owners and administrators
may refund their payments. The dedicated `order.reconcile` scope also authorizes
the internal cleanup worker. This scope grants financial mutation privileges;
provision it only for the trusted recovery identity described in
[order cleanup](order-cleanup.md).

## Request and state contract

Every new refund requires a printable ASCII idempotency key of 1–255 characters.
Reusing a key requires the same payment, exact amount/currency and reason.
Empty and absent reasons are equivalent. Changed details fail validation, including
after completion. REST requires a positive integer minor-unit amount; gRPC may
omit amount to refund the remaining balance. An omitted amount is bound once to
the original intent; retries do not recalculate it from a changed balance.

The executor commits a PENDING intent before provider I/O. Short transactions
serialize admission on the full key and payment; only one unresolved refund may
exist per payment. A pending or uncertain outcome reserves the balance and blocks
another key. Payment balances change only on provider success. A failed refund
remains FAILED when retried with its original key; a new business attempt requires
a new key and a check of the provider's final outcome.

REST returns 202 for PENDING, 200 for COMPLETED and 422 for FAILED. gRPC returns
the equivalent refund status. Both reject conflicting requests, invalid amounts
and unsupported payment/provider states. The gRPC adapter redacts error details
and enforces owner, administrator or recovery-worker authorization through the
shared service. Existing unkeyed gRPC refund clients must supply a key before
upgrading; this tightens the earlier optional-key behavior without changing proto
field numbers.

## Provider dispatch, leases and webhooks

Migration V5 adds a durable provider key and paired attempt ID/expiry fields.
New intents use `omp-refund-{refund UUID}` as their provider key. Stripe receives
this key in its Idempotency-Key header and the local refund UUID in metadata.
HTTP refund responses must match the requested payment, amount and currency.
HTTP errors, including conflicts and authentication failures, remain uncertain;
only an explicit terminal refund status releases or applies the reserved balance.
The executor leases an intent for two minutes and contacts the provider outside
database transactions. Replies can update state only with the current lease.
An uncertain response leaves the same intent available for retry; a lost reply
does not generate a new provider key.

Stripe v1 can prune keys after at least 24 hours. Automatic POST replay stops
20 hours after local intent creation, allowing margin before this boundary.
Such intents remain pending for reconciliation. See Stripe's
[idempotent request contract](https://docs.stripe.com/api/idempotent_requests).
Once a provider reference is known, the original creation request returns the
pending intent. Recovery reads the existing provider refund instead of sending
another POST. Stripe refund
statuses include pending, requires_action, succeeded, failed and canceled; see
the [refund object contract](https://docs.stripe.com/api/refunds/object).

## Recovery worker and operator actions

Migration V6 adds attempt counts, next-attempt time, a redacted error code and an
attention reason. The scanner selects at most ten due pending intents per tick.
Each claim locks the payment first with SKIP LOCKED; busy payments and live leases
are skipped. This matches webhook/completion lock ordering. The existing
two-minute lease fences late responses. Retry backoff starts at 60 seconds and
increases to at most 15 minutes. No database transaction spans provider I/O.

Enable the worker explicitly:

```properties
payment.refund.recovery.enabled=true
payment.refund.recovery.interval-ms=30000
```

The interval must be between one second and one hour. The worker owns a separate
single daemon thread so a slow provider cannot block the transactional-outbox
scheduler. It uses the payment-service provider credentials already configured
for dispatch. It does not relay a customer's JWT or require the order worker's
recovery JWT. Shutdown interrupts the worker and waits up to five seconds; an
unfinished database lease remains recoverable after expiry.

Known provider references use Stripe's read-only
[GET refund endpoint](https://docs.stripe.com/api/refunds/retrieve), including
after the POST replay window expires. The adapter verifies refund ID, payment
reference, exact amount and currency before accepting a status. A 404, transport
failure, unsupported status or mismatched response remains unresolved; it never
causes a replacement refund. The development noop provider can only look up
entries still present in its in-memory ledger; that provider is not durable.

An uncertain outcome without a provider reference may retry the original POST
with its durable key within the replay window. Expired uncertain intents, legacy
intents, provider mismatches and conflicting payment states receive an attention
reason and leave the automatic scan. A verified pending webhook can supply the
missing reference and clear an expired intent's attention flag, allowing GET
recovery without reopening POST replay. Pending webhooks preserve an active
attempt lease; terminal webhooks fence the late reply through terminal state.

Public operations:

| Operation | Authorization | Result |
|---|---|---|
| `GET /api/v1/payments/{paymentId}/refunds/{refundId}` | Payment owner or administrator | Refund and recovery state, without idempotency/provider keys |
| `POST /api/v1/payments/{paymentId}/refunds/{refundId}/retry` | Administrator | Retry one existing intent; 200 completed, 202 pending, 422 failed |

The recovery scope alone cannot invoke the administrator action. Manual retry may
bypass backoff, but cannot replace the refund, change its amount, override a live
lease, rebind a provider or bypass the POST replay cutoff. Terminal retries make
no provider call. Missing refunds and payment/refund mismatches return 404.

The inspection response nests the original refund under `refund` and adds
`attempts`, `nextAttemptAt`, `lastError` and `attentionReason`. Stored error codes
exclude provider payloads. Attention reasons are `LEGACY_INTENT`,
`REPLAY_WINDOW_EXPIRED`, `PROVIDER_MISMATCH` and `PAYMENT_STATE_MISMATCH`. Inspect
the provider ledger and the local payment/refund/outbox history before resolving
an attention case; do not use a new key or clear a row to force financial progress.

Monitor `payment.refund.pending`, `payment.refund.attention`,
`payment.refund.recovery.attempts` (dispatch/lookup),
`payment.refund.recovery.outcomes` (bounded provider statuses),
`payment.refund.recovery.failures` and `payment.refund.recovery.worker.failures`.
The attention gauge includes migration-flagged legacy rows. Prometheus converts
dots to underscores. Receiver routing and alert exercises remain unverified.

Verified `refund.created`, `refund.updated` and `refund.failed` events correlate
using local refund metadata or an already recorded provider reference. The handler
checks payment/provider ownership, provider payment reference, refund identity,
exact minor amount and currency. Terminal success/failure may arrive before the
HTTP response. Webhook receipt, refund/payment changes and the success outbox
event commit together. Replayed events, terminal duplicates and stale replies
cannot apply the amount again. Success emits the canonical full or partial
refund event through the existing transactional publisher.

Aggregate `charge.refunded` echoes are audited as ignored; applying them would
double count an individually reconciled refund. Out-of-band refunds without a
local intent are also ignored and require an operator audit. Configure delivery
of refund events at the provider, including refund.updated and refund.failed.

## Cancellation cleanup

Cleanup validates payment and order IDs before mutation. It cancels a PENDING
payment and requests the remaining refund for CONFIRMED or PARTIALLY_REFUNDED
payments. The refund key is the durable cleanup task ID, prefixed with
`order-cleanup-`. Pending/failed refunds or invalid responses leave the cleanup
task pending. Even a completed refund response requires a fresh terminal payment
read before acknowledgement. CANCELLED, FAILED and fully REFUNDED payments are
already resolved. Confirmation winning a cancellation race is handled by the
next state-aware retry.

Cleanup and refund executors reject ambient transactions. Cleanup preserves
interruption before starting work. Existing task lease fencing, retry backoff,
redacted errors and metrics continue to apply.

## Rollout and verification limits

Apply V5/V6 and deploy payment-service before enabling the worker or using its
operator endpoints. Deploy payment-service before the order cleanup client. Newly created
gRPC intents record the configured provider. Existing payments without a provider
binding, or with a provider different from the configured implementation, require
an audit before refund dispatch. No automatic provider reassignment is performed.

Legacy PENDING refunds have no durable provider key and cannot be automatically
replayed. Reconcile their provider ledger first: the old REST transport did not
send a provider idempotency header, and the old gRPC path recorded synthetic local
completion. Historical COMPLETED records are preserved. Do not clear a pending
row or use a new key merely to bypass an uncertain outcome or the replay cutoff.

Read-only inspection:

```sql
SELECT id, payment_id, status, amount, currency, provider_reference,
       recovery_attempts, next_attempt_at, last_error, attention_reason,
       attempt_until, created_at, completed_at
FROM payments.refund
WHERE status = 'PENDING'
ORDER BY created_at, id
LIMIT 100;
```

Local tests exercise real in-process gRPC, method authorization, controlled
provider outcomes, webhook races, duplicate events, leases and cleanup decisions.
Stripe HTTP tests verify the real header, metadata and status classification.
The PostgreSQL suite adds intent visibility before provider I/O, competing keys,
atomic completion/outbox rollback, early-webhook receipt, busy-payment skipping,
committed recovery metadata and late pending-webhook recovery. All thirteen
payment/refund PostgreSQL cases passed locally on PostgreSQL 16.4, including
audited reconciliation, atomic audit/refund/outbox rollback and duplicate binding
rejection. See the implementation report for the Docker client, schema mapping
and repository-query fixes needed to run them. These tests do not establish deployed provider
delivery, real account behavior, mTLS, replica recovery or full-stack acceptance.
Known-reference missing webhooks now recover automatically through provider GET.
Unknown-reference expired refunds now have an
[audited administrator reconciliation action](refund-reconciliation.md). It reads
an operator-supplied provider reference and requires matching original intent
metadata before binding it or applying money. Automatic provider-list discovery
and legacy data repair remain unfinished.
Deployed recovery, metrics/alert exercises and legacy audit remain open. The
broader refund and cancellation tasks remain unchecked.

Contradictory provider outcomes after a locally terminal refund require an
operator audit; this implementation does not reverse an already posted refund.
