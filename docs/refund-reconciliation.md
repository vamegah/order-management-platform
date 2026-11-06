# Audited refund reconciliation

This action resolves a new-style pending refund whose provider reference is
unknown locally. An administrator supplies a reference found in the provider
ledger and a reason for the investigation. The server reads that existing
provider refund and checks its identity before changing local financial state.
It never creates a provider refund, changes the requested amount, replaces the
local refund ID or generates a new provider idempotency key.

Use the existing [recovery worker and retry action](payment-refunds.md) for
routine known-reference recovery. The reconciliation action is useful after an
uncertain POST's replay window has expired. Legacy intents without a bound
provider key still require a separate audit and cannot be rebound through this
API.

## Upgrade preflight

Apply migration V7 before using the new endpoints. It adds the reconciliation
audit table and a unique payment/provider-refund binding. Investigate existing
duplicates before migration:

```sql
SELECT payment_id, provider_reference, count(*) AS bindings
FROM payments.refund
WHERE provider_reference IS NOT NULL
GROUP BY payment_id, provider_reference
HAVING count(*) > 1;
```

V7 deliberately fails on duplicates rather than deleting, merging or rewriting
financial history. Follow the provider ledger and the payment/refund/outbox
records to resolve discrepancies through a reviewed data repair. Existing
terminal records are preserved. Deploy compatible payment writers before using
the action; the application locking protocol does not govern external SQL.

## Request and authorization

Both endpoints require a validated administrator JWT. Payment ownership and
`order.reconcile` alone do not authorize reconciliation or audit-history access.
The actor subject and issuer come from the authenticated JWT, not the request
body. The subject must be present, bounded and free of control characters.

```http
POST /api/v1/payments/{paymentId}/refunds/{refundId}/reconciliation
Authorization: Bearer <administrator JWT>
Content-Type: application/json

{
  "providerReference": "re_example",
  "reason": "Provider ledger investigation for the lost response"
}
```

The candidate must be a bounded Stripe refund ID (`re_` followed by letters,
digits or underscores). The reason must contain 1–500 UTF-16 code units, include
non-whitespace text, and exclude NUL. The action accepts no amount, actor,
currency or replacement key. Invalid input returns 400; unauthenticated and
unauthorized callers receive 401 and 403. Payment/refund path mismatches and
missing refunds return the existing redacted 404 response.

The response contains `recovery` (the existing refund/recovery view) and `audit`
(the reconciliation attempt). Provider and idempotency keys are omitted.
Every valid request reaching an existing refund creates its own audit entry,
including busy, blocked and repeated terminal requests. Repeating a completed
action does not apply money again.

## Provider proof and transaction boundaries

The first short transaction locks the payment, validates the refund/provider
binding, records the operator's intent and claims the existing two-minute refund
lease. It commits before provider I/O. A live lease produces BUSY without a
provider call. An already bound reference cannot be replaced, and the local
provider key must match the original `omp-refund-{refund ID}` format.

The strict Stripe adapter uses the
[refund GET endpoint](https://docs.stripe.com/api/refunds/retrieve). It checks:

- Returned refund ID equals the candidate reference.
- PaymentIntent reference, exact minor-unit amount and currency match the intent.
- Provider `metadata.refund_id` equals the local refund UUID.
- Provider `metadata.payment_id` equals the local payment UUID.

Matching amount alone is insufficient: two partial refunds can have identical
amounts. The metadata requirements bind this discovery to the original request.
The normal dispatch path writes both metadata fields. Unsupported providers fail
closed instead of falling back to an unverified lookup or a POST. The development
noop provider does not implement strict reconciliation.

HTTP errors, missing/incorrect metadata, mismatched identity and unknown provider
statuses leave the candidate unbound. Provider payloads are not persisted in the
audit or returned as errors. A successful verified pending response records the
reference, clears its attention flag and enables normal GET recovery. A verified
success applies the original amount; verified failure marks the intent failed
without applying money.

The final short transaction checks the current lease, payment balance and unique
reference binding again. Refund/payment changes, the success outbox event and
the audit outcome commit together. A webhook or newer lease can supersede the
operator attempt without posting money again. Failures during finalization roll
back this entire transaction, leaving the earlier IN_PROGRESS audit and lease
for inspection and safe retry after lease expiry.

## Outcomes

| Audit outcome | HTTP | Meaning |
|---|---|---|
| IN_PROGRESS | Visible in history | Intent committed; the request may still be running or may have crashed |
| BUSY | 202 | Another attempt owns the lease |
| BLOCKED | 409 | Provider, legacy binding, payment state or reference conflict prevents reconciliation |
| UNRESOLVED | 202 | Provider read or strict identity verification did not resolve the candidate |
| PENDING | 202 | Verified reference recorded; normal recovery can continue |
| COMPLETED | 200 | Original refund applied with its financial event |
| FAILED | 422 | Provider explicitly reported terminal failure; no amount applied |
| NO_CHANGE | 200/422 | Refund was already locally terminal; no provider read or new application |
| SUPERSEDED | 200/202/422 | Webhook or another attempt won; response reports current local state |

Pending responses include Retry-After: 60. Audit detail codes distinguish
`REFERENCE_CONFLICT`, `REFERENCE_IN_USE`, `LEGACY_INTENT`, `PROVIDER_MISMATCH`,
`PAYMENT_STATE_MISMATCH` and `PROVIDER_UNRESOLVED`. A local terminal/NO_CHANGE
response does not attest a historical provider outcome. Historical synthetic or
contradictory terminal records still need ledger review.

## Audit inspection

```http
GET /api/v1/payments/{paymentId}/refunds/{refundId}/reconciliation
Authorization: Bearer <administrator JWT>
```

This returns at most the 20 most recent entries, ordered by creation time and ID
descending. It exposes operator subject/issuer, reason, candidate, outcome,
detail code and timestamps. Owners cannot read these operational records.
Request fields are immutable through the application mapping; the audit is not
tamper-proof against privileged database writers. No automatic deletion is
configured; define audit access, backup and retention policy before production.

Read-only incident inspection:

```sql
SELECT id, refund_id, candidate_reference, actor_subject, actor_issuer,
       reason, outcome, detail_code, created_at, completed_at
FROM payments.refund_reconciliation_audit
WHERE refund_id = :refund_id
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

The bounded `payment.refund.reconciliation` counter is tagged by audit outcome.
Long-lived IN_PROGRESS records require checking the refund's current lease and
provider ledger. Do not clear a row, remove the unique index or issue a new refund
merely to force progress. A terminal failed refund can require a new, separately
authorized business request after ledger review; reconciliation itself never
creates that request.

## Verification limits

Tests exercise actual domain/store/service behavior, provider HTTP metadata and
identity checks, production HTTP authorization policy, stale leases, webhook
races, duplicate binding and redacted errors. JWT decoding is controlled in local
HTTP tests. PostgreSQL tests cover audit visibility before I/O, pending-to-final
results, atomic audit/refund/outbox rollback and the unique reference constraint.
All thirteen payment/refund PostgreSQL cases passed locally, including the three
new reconciliation scenarios, with zero failures, errors or skips. Payment unit
and in-process tests passed all 194 cases; the reactor's unit reports total 609.
The Docker Desktop engine is accessible through its Windows named pipe; the
Docker CLI is not needed for this suite. See the implementation report for
executed results and the Docker API compatibility fix. Run the focused database
suite from the repository root:

```sh
./mvnw -pl payment-service -am test-compile \
  failsafe:integration-test failsafe:verify \
  -Dit.test=PaymentCreationConcurrencyIT -Dfailsafe.failIfNoSpecifiedTests=false
```

This focused run does not replace full `clean verify` and its coverage gates.
Deployed provider access, JWT/mTLS, full concurrent recovery acceptance, ledger-search
automation, legacy data repair and audit/alert operational exercises remain open.
