# Order verification and access policy

The November 7 recovery-discovery continuation validates each complete lookup
result before adding its cleanup tasks: every resource must belong to the
journal attempt's order, have a distinct non-null identity and fit the existing
100-resource bound. Generated inventory and payment clients exercise foreign
ownership, malformed UUIDs, duplicate identities, oversized results, corrected
response retry, continued unrelated attempts, maximum valid batches and empty
lookups. Invalid results do not queue a valid-looking prefix. Verified
reservations remain queued when a later payment lookup fails, and existing
cleanup retries still run.

The clean seven-module order/shared static-analysis build passes 318 unit and
42 PostgreSQL/HTTP integration cases without failures, errors or skips, including
all thirteen new real-RPC cases. Six complete static reports have zero findings,
errors or missing classes; all five coverage policies pass. Order coverage is
82.77% against its unchanged 80% gate, and the evidence checker accepts all 214
order-service cases. Source-bound reports are retained in
`coverage/checkout-recovery-discovery-evidence.zip` and JSON; log:
`checkout-recovery-discovery-verification.log` (8:44, exit 0). This local scope
does not establish deployed workload identity or process-crash recovery.

Order integration tests run HTTP, JPA, Flyway and the transactional outbox against
PostgreSQL 16.4. Product/customer HTTP calls use WireMock, and inventory/payment
RPCs use in-process fixture servers through the real generated clients. These
tests do not establish deployed JWT issuer validation, mTLS, broker delivery or
the real inventory/payment services' behavior.

## Public authorization

| Operation | Required access |
|---|---|
| List orders | Administrator |
| Read by UUID or order number | Administrator or verified owner |
| Create an order | Administrator or matching requested customer |
| Place or cancel an order | Administrator or verified owner |
| Confirm, ship or deliver | Administrator |
| Inspect or restore historical reservation references | Administrator |
| Admit a reviewed historical orphan to recovery | Administrator |

Ownership uses a canonical UUID string in the verified JWT's `customer_id` claim.
It does not infer customer identity from the subject or request body. Existing
orders are checked against their persisted customer ID. Missing, malformed or
non-string claims deny owner access before ownership queries. Foreign or missing
orders return 403 to non-owners; administrators retain ordinary 404 responses for
missing resources. Method-security denials use HTTP 403 problem details.

Clients with a legacy token lacking `customer_id` need an updated issuer claim
before using owner routes. Administrator access remains available. The incoming
JWT decoder remains the production authority for signature/issuer validation;
test credentials replace that decoder only in fixtures.

Historical NULL reservation references have a separate administrator repair
resource. It verifies existing inventory holds outside the database transaction,
then restores a complete version-checked plan with an atomic per-line audit and
one aggregate version increment. It cannot replace references, allocate stock or
repair terminal orders. See [the operator procedure](order-reservation-repair.md)
for request, retry, audit and unresolved-data behavior.

The clean November 7 historical-repair build passes all 435 cases: 384 unit and
51 PostgreSQL/HTTP integration cases, including 46 new regressions. Six complete
static reports contain zero findings, errors or missing classes; all five
existing coverage policies pass. Order instruction coverage is 84.59% against
its unchanged 80% gate, and the evidence checker accepts all 289 order-service
cases. Real database cases cover audited idempotent repair, complete multi-line
plans, audit failure rollback and both directions of an optimistic concurrency
race. HTTP cases verify administrator access, validation, matching retry and
foreign-reservation rejection through generated inventory clients. Reports,
exact source and the 9:13 successful build log are retained in
`coverage/legacy-reservation-repair-evidence.zip` and JSON;
log: `legacy-reservation-repair-verification.log`.

A separate clean unit-only package/report build passes all 384 cases in 3:09.
All five focused module comparisons pass the unchanged CI instruction policy
against the accepted main source. Order counts improve from 3,819 covered/2,727
missed to 4,250/2,994. Its reports and baseline source proof are retained in
`coverage/legacy-reservation-repair-unit-evidence.zip` and JSON;
log: `legacy-reservation-repair-unit-verification.log`. This preflight leaves the
complete fourteen-module PR comparison and deployed repair acceptance separate.

## Startup and persistence repairs

Checkout also validates payment-intent replies before retaining a payment ID.
The order, customer, currency and positive bounded decimal amount must match
the request; numeric equality permits equivalent decimal representations. The
reply must have a valid UUID and be pending, or already confirmed with a provider
reference. A matching confirmed replay remains valid because the payment
service's idempotency lookup returns the existing payment's current state.
Cancelled, failed, refunded, unspecified and unknown results are unusable for
checkout. Invalid replies return the existing generic downstream HTTP 502
contract before aggregate acceptance. Only verified reservations enter immediate
compensation; an unverified payment identity remains on journal discovery.
If a verified payment precedes a failed local acceptance transaction, both that
payment and its known reservations still enter compensation.

The November 7 payment-correlation build passes a clean seven-module
`-pl order-service -am -Pstatic-analysis spotless:apply clean verify`: 347 unit
and 42 PostgreSQL/HTTP integration cases without failures, errors or skips.
All twenty-nine new generated-client cases pass. Six complete static reports
contain zero findings/errors/missing classes, and all five existing coverage
policies pass; order coverage is 83.15% against its unchanged 80% gate. The
evidence checker accepts all 243 order-service cases. Reports and exact source
are retained in `coverage/checkout-payment-correlation-evidence.zip` and JSON;
log: `checkout-payment-correlation-verification.log` (8:25, exit 0).

Checkout accepts an inventory reservation reply only when its order, SKU,
warehouse, quantity and active state match the request and its identity parses as
a UUID. Distinct order lines must receive distinct reservation IDs. Invalid
replies fail as downstream HTTP 502 errors before payment creation or aggregate
acceptance. The checkout journal records the failed attempt, and immediate
compensation queues only verified earlier holds. Unknown side effects remain
discoverable through the separately configured recovery worker; an unverified
foreign response is never added as a cleanup reference.

- HTTP and gRPC authentication interceptors have distinct bean names, and JPA
  auditing is enabled once through the existing JPA configuration.
- Order email matches the existing `citext` column. Currency/country mappings
  match PostgreSQL `bpchar`; monetary precision is `numeric(19,4)`. Historical
  Flyway migrations remain unchanged, and tests validate the actual schema.
- Spring owns each HTTP test class's database and RPC server lifetimes. HTTP
  stub ports remain JVM-scoped and their stubs reset before each test.
- RPC fakes reside in test sources. They are not packaged with order-service.
  JSON stubs serialize product names correctly, including quoted names.
- `app.cors` and `app.pagination` bind at the root. Browser preflights honor the
  configured origin list before authentication. Money sorting uses `total.amount`;
  the nonexistent `totalAmount` sort is rejected before querying orders.
- Malformed JSON returns HTTP 400; unsupported request media return HTTP 415
  with a matching problem-details status. Test JWT decoders model invalid credentials
  as 401 and do not echo bearer tokens in failure messages.

## Verification

Reservation cleanup reads the remote reservation before mutation and requires
both its ID and order ID to match the durable task. Already released or expired
reservations complete without another mutation. Only active reservations are
released, and the response must still match both IDs and be released or expired
before the task is acknowledged. Missing/mismatched identities, committed or
unknown states, failed calls and unresolved responses retain the task with its
existing backoff and failure metric. Retrying a lost release response re-reads
the final state, so an already released hold needs no further mutation.

The reservation cleanup regressions exercise these outcomes and ensure that
RPCs follow the task-claim transaction. Existing HTTP/PostgreSQL boundary tests
exercise cancellation and repeated cleanup through generated inventory clients.
Complete deployed compensation and legacy-row repair remain separate acceptance
items in `TASKS.md`.

With Java 21 and a reachable Docker engine, run from the root:

```sh
./mvnw --batch-mode --no-transfer-progress --fail-at-end \
  -pl order-service -am clean verify
```

For static analysis, add `-Pstatic-analysis`. Tests exercise committed reservation
references, checkout acceptance, optimistic concurrency, history/outbox rollback,
fulfillment leases/finalization and transaction boundaries. The HTTP boundary
suite additionally exercises owner/admin policy, malformed requests, sorting,
CORS, pending-payment rejection, paid shipment and cancellation retries.

The outbox drainer and Kafka topic provisioning are disabled in HTTP fixtures so
assertions inspect committed outbox rows without requiring a broker. Persisting
an event is separate from proving its delivery. Existing 80% coverage enforcement
is retained.

Actual execution results and remaining full-reactor/e2e acceptance are recorded
in [the implementation report](task-implementation-report.md).

The November 6 cleanup continuation passes a clean seven-module
`-pl order-service -am -Pstatic-analysis spotless:apply clean verify` build in
7:55. Its reports contain 289 unit cases (including seven protobuf compatibility
cases) and all 42 order integration cases, with no failures, errors or skips.
All twenty new reservation cleanup cases pass. Six complete static reports have
zero findings/errors/missing classes; all five coverage policies pass, including
the unchanged 80% order gate at 81.54%. The order evidence checker independently
accepts all 185 order cases. Source-bound reports are retained in
`coverage/order-reservation-cleanup-evidence.zip` and JSON; log:
`order-reservation-cleanup-verification.log`. This local follow-up is separate
from the accepted migration PR/default-branch workflows.

The separate checkout-correlation continuation passes the same clean seven-module
build in 7:37: all 305 unit and 42 integration cases pass, including sixteen new
real-RPC/service cases. The partial-checkout case rejects a foreign second reply,
queues only the verified first hold, and prevents payment or aggregate acceptance.
All five coverage gates pass (order 81.84% against its unchanged 80% gate), and
six complete static reports contain zero findings/errors/missing classes. The
order evidence checker accepts all 201 order cases. Source-bound reports are in
`coverage/checkout-reservation-correlation-evidence.zip` and JSON; log:
`checkout-reservation-correlation-verification.log`. This local follow-up is
separate from PR #3's accepted cleanup workflow.
## Durable worker failure fixture

`CheckoutRecoveryWorkerIT` adds ten default PostgreSQL integration cases using
the actual worker/journal/cleanup transactions and generated RPC endpoints.
Four cases cover partial discovery, a lost release acknowledgment, lease
fencing and token-file replacement; six parameterized cases protect accepted
active order states. Assertions check durable rows, recovery/cleanup metrics,
synthetic worker identity forwarding and RPCs outside database transactions.

Ticks are invoked directly and database eligibility is advanced explicitly.
Process termination, scheduler timing, elapsed lease recovery and full-service
failure acceptance remain separate. See [the fixture command and scope](order-cleanup.md#worker-transaction-and-retry-verification).

The reconstructed seven-module build passes formatting, compilation and all
three targeted `CheckoutRecoveryJobTest` unit cases without failures, errors or
skips. The ten new PostgreSQL cases compile but have not executed locally because
the Docker engine is unavailable. Source-bound evidence is retained in
`coverage/recovered-worker-failure-acceptance-local-evidence.zip` and JSON.
Full PR CI subsequently executes all ten cases without failures, errors or skips;
see [the PR/default-branch record](task-implementation-report.md#durable-worker-pr-and-default-branch-acceptance).

## Worker process termination fixture

`CheckoutRecoveryProcessIT` selects three default PostgreSQL cases using separate
worker JVMs and loopback generated RPC endpoints. It kills a worker after a
synthetic remote release, pending cancellation or remaining refund but before
acknowledgment, verifies live-lease exclusion from a replacement process, waits
for PostgreSQL's real lease deadline, and checks that new processes acknowledge
and preserve completion without repeating the mutation. Refund requests must
omit the amount and use the key derived from the durable cleanup task. The
fixture's refund identity, amount and terminal payment must remain unchanged.
Durable attempts/leases, pending/completion metrics, identity forwarding and
transaction boundaries are asserted. No lease/retry timestamp is advanced by SQL.

The fixture uses manual ticks and synthetic credentials. It does not establish
scheduler/container restart, downstream-service restart, other payment/refund crash
recovery or production identity acceptance. See [the focused command and scope](order-cleanup.md#worker-process-and-elapsed-lease-verification).

The original focused seven-module `package failsafe:integration-test failsafe:verify` run
passes three worker unit and all eleven selected PostgreSQL cases without
failures, errors or skips. The process case takes 166.207 seconds, including real
lease expiry. Formatting/compilation and a separate JVM-launch preflight pass;
the latter handles a 50,730-character classpath and paths containing spaces.
Exact-source local reports and logs are retained in
`coverage/checkout-recovery-process-local-evidence.zip` and JSON. This focused
command did not run the complete reactor or its combined coverage gate. Its
later [PR #12 acceptance](https://github.com/vamegah/order-management-platform/pull/12)
executed 1,193 reactor and 21 deployed cases; see the implementation report.

The payment crash follow-up passes formatting, compilation, three focused worker
unit and all thirteen PostgreSQL cases without failures, errors or skips. The
cancellation/refund process cases take 138.277/134.130 seconds, and the original
release case takes 158.039 seconds. Each waits for the real lease deadline.
Long-classpath JVM-launch preflight also passes. Exact-source reports, compiled
classes and logs are retained separately in
`coverage/checkout-recovery-payment-crash-local-evidence.zip` and JSON.
[PR #14](https://github.com/vamegah/order-management-platform/pull/14) and its
merged main workflow pass 1,195 reactor and 23 deployed service/client cases,
with each of the three process cases exceeding 120 seconds. Exact CI reports
and approved merge records are retained in the payment crash evidence archives;
see the implementation report. The earlier evidence belongs to the original fixture.

## Spring-managed scheduler fixture

`CheckoutRecoverySchedulerIT` adds one required default PostgreSQL case with actual
Spring scheduling and the production recovery bean. It exercises invalid-token
exclusion, atomic projection replacement, automatic cleanup, two contexts against
the same database and continued ticks without repeat mutations. Executor shutdown,
acknowledged metrics, durable attempts/leases and worker identity/transaction
boundaries are asserted. No worker tick is invoked and no database eligibility
timestamp is advanced manually. See [the focused command and scope](order-cleanup.md#spring-scheduler-and-context-restart-verification).

Formatting/compilation, three worker unit and eleven PostgreSQL cases pass without
failures, errors or skips. The scheduler method takes 12.795 seconds. Exact
source, reports, classes and logs are retained in
`coverage/checkout-recovery-scheduler-local-evidence.zip` and JSON.
[PR #15](https://github.com/vamegah/order-management-platform/pull/15) and its
merged main workflow pass all 1,196 reactor and 23 deployed cases; source-bound
reports and approved merge/protection records are retained in the scheduler
PR/main CI archives. Synthetic in-process
endpoints and same-JVM context restart do not establish full Boot/container/JVM or
downstream restart or deployed scheduler identity acceptance.

## Deployed scheduled-worker fixture

`ScheduledRecoveryIT` runs the actual scheduled Boot application, supervised
private projector and original JWT/mTLS service containers. The final focused
Linux build passes 70 unit and two deployed cases without failures/errors/skips.
The methods run for 298.571 and 307.516 seconds, including container restarts,
projection renewal, automatic refund/cancellation and real five-minute backoff.
Only verified test-owned resources are mutated. No direct worker ticks or SQL
eligibility updates accelerate recovery.

See [the isolated deployment command and scope](external-e2e.md#scheduled-recovery-acceptance)
and [the implementation report](task-implementation-report.md#supervised-deployed-checkout-recovery-2026-11-08).
Exact-source local evidence is retained in
`coverage/scheduled-recovery-deployment-local-evidence.zip` and JSON. Complete CI
must execute all 1,196 reactor and 25 deployed cases before acceptance. Production
identity/provider behavior and expiry/compensation for accepted orders remain open.
