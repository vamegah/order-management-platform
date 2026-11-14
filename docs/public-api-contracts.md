# Public REST and GraphQL contracts

The six OpenAPI 3.1 documents under `contracts/openapi/v1` and the GraphQL v1 SDL
are the published client artifacts. The API inventory includes payment-provider
callbacks and administrator lifecycle/refund/DLQ endpoints. Actuator, Swagger UI,
OIDC endpoints owned by Keycloak and the separate STOMP transport are not REST
business operations in these documents.

## Implementation drift and compatibility

Every producer generates its document independently of the checked-in contract.
Shared metadata gives operations stable `Controller_method` identities and derives
`x-implemented-routes` from Spring MVC mappings, including hidden routes. The
checker requires that inventory to match all published method/path pairs, then
compares live schemas, parameters, responses, security and method-authorization
metadata with the published version. It never updates a fixture while checking.

The required contract CI job checks out the exact PR base. Replacing a head
fixture together with the implementation cannot hide a breaking change. Existing
operations, model identities, parameters, response properties, media types,
success statuses and authentication schemes remain supported. Input constraints
may widen and output constraints may narrow; optional fields and new operations
are additive. Changes to complex constraints that cannot be proved compatible
are rejected conservatively. Publish a new major API version for incompatible
changes.

GraphQL comparison uses the reference schema utilities. Existing fields,
arguments, defaults, enum and union outputs remain compatible with the generated
consumer. Input-only enum additions and optional fields/arguments remain allowed.
The serialized scalar contract is checked separately: `Decimal` output is a plain
decimal **string**, Date is an ISO date, and DateTime is an ISO offset/UTC timestamp.
This prevents a scalar representation change from escaping an unchanged SDL.
The first REST publication establishes its v1 baseline; GraphQL also compares the
base implementation SDL when the base predates the published fixture.

## Supported behavior

- Catalog reads require a JWT; product writes require an administrator. Filter
  and controller denials use the same safe JSON problem contract. Full product
  category fields resolve through a request-scoped, deduplicated lookup.
- Private customer data requires the verified owner or administrator. Preferences
  are administrator updates; omitted/null fields preserve existing values, and a
  new record gets en-US/USD with email on and marketing/SMS off.
- Order listing requires an administrator or an explicit customerId matching the
  verified owner. Upstream ownership denials remain GraphQL FORBIDDEN. Mutation
  history is nullable; use canonical order queries to read the complete ledger.
  The owner filter is passed to the authority. Inclusive placement
  windows use `placedAt`, not creation time, and reject inverted bounds.
- Webhook subscriptions/history are owner-scoped; DLQ and replay require an
  administrator. Secrets are returned only when created or rotated.
- Payment/refund access follows the payment owner and recovery policies. Signed
  provider callbacks use their provider header rather than bearer authentication;
  the Noop signature is a development fixture.
- Banking is administrator-only because account ownership is not modeled by the
  facade. Exact transfer retries reuse the documented Idempotency-Key behavior.

The GraphQL HTTP consumer supports queries and mutations. The reserved
Subscription SDL declaration remains unsupported; live notifications use STOMP.
The [gateway policy](graphql-production-policy.md) defines full-text-only queries,
no-store responses, production introspection/GraphiQL settings and execution limits.
Production banking transport rotation and broader operations/release acceptance
remain in the banking and operations backlog.

## Local contract checks

Use Python 3.12 for CI parity:

```sh
python -m pip install -r scripts/contracts-requirements.lock
python -m unittest discover -s scripts/contracts_tests -v
python -m unittest discover -s clients/public-api/tests/unit -v
python scripts/check_api_contracts.py --base-root /path/to/exact-base-checkout
python clients/public-api/generate.py --base-root /path/to/exact-base-checkout
```

The compiler reads published JSON/SDL only, validates current and prior consumer
operations against the new schema, and emits/compiles the standalone client in
`clients/public-api/build`. The runtime validates request/response schemas,
status/media contracts, selected GraphQL fields, nullability and custom scalar
formats. It resolves tokens per request, follows no redirects and performs no
automatic mutation retries.

## Deployed consumer acceptance

The required `public-api-e2e` job starts an isolated project through Traefik with
the actual issuer, packaged services, both gateway profiles and the SOAP bank.
It uses the Noop payment provider and test-owned resources. A separate banking
certificate and truststore verify the SOAP hostname against the development CA.
Existing identities are preserved by `scripts/setup-public-api-acceptance.sh`.
Fresh development realm imports give Alice the stable `test-alice` subject already
linked by the customer seed migration. Existing issuer databases are not rewritten.

Combine these files after provisioning the private UID-1000 projector identity as
in [lifecycle deployment](core-order-lifecycle.md):

```sh
export COMPOSE_PROJECT_NAME=omp-public-api-local
bash scripts/setup-public-api-acceptance.sh
# Set ORDER_RECOVERY_TOKEN_DIRECTORY and ORDER_RECOVERY_CLIENT_SECRET_FILE to
# private absolute paths; application UID 1000 must own/read them.
docker compose --env-file .env.example \
  -f docker-compose.yml -f docker-compose.e2e.yml \
  -f docker-compose.lifecycle.yml -f docker-compose.recovery-projector.yml \
  -f docker-compose.public-api-e2e.yml up -d --build --wait \
  traefik product-service customer-service order-service inventory-service \
  payment-service notification-service graphql-gateway graphql-gateway-prod \
  legacy-banking-adapter
python clients/public-api/export_live.py
python scripts/check_api_contracts.py --live-root clients/public-api/build/live
python clients/public-api/generate.py
PUBLIC_API_E2E=1 python clients/public-api/run_deployed.py
python scripts/check_junit.py clients/public-api/deployed-results.xml --minimum-tests 30
```

Resolve the development hostnames through the local Traefik instance (CI supplies
host entries). Docker Desktop must use Linux-backed private identity volumes when
NTFS cannot provide the required file ownership/modes. The acceptance overlay uses
a 60-request identity window to exercise limits; normal gateway defaults remain
120. The Traefik acceptance dashboard is disabled and its health endpoint binds to
container loopback.

The consumer checks authentication/ownership, pagination, batch ordering, mutation
paths, payment/refund/banking retries, nullable and redacted errors, observed
DataLoader batch counts, and production query policy. Missing/skipped cases fail
the JUnit guard. Fixture credentials are read only by the development harness and
are never printed. Published contracts are reviewed source files, not build output.

## Executed acceptance (2026-11-14)

The isolated packaged deployment passes all 30 consumer cases with no failures,
errors or skips. All six live documents match the published v1 artifacts and
pass OpenAPI 3.1 validation. Exact-main GraphQL compatibility and all 28 contract
regressions pass; the 18 client tests and compilation of 21 operations also pass.
Required CI repeats these checks from a clean checkout and independently built
images before a merge can satisfy the quality gate.
