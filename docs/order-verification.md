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
