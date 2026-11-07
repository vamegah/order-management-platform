# Order verification and access policy

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

## Startup and persistence repairs

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
