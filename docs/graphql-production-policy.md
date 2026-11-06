# GraphQL execution and production policy

The gateway accepts authenticated full-query requests at `/graphql`. Hash-only
persisted queries and automatic persisted-query negotiation are not supported.
Requests carrying a `persistedQuery` extension are rejected explicitly with
`PERSISTED_QUERIES_UNSUPPORTED`; hash-only requests without a document fail
transport validation. There is no persisted-query registry to publish or revoke.

GraphQL HTTP responses carry `Cache-Control: no-store`, including limit errors.
Responses contain user-specific aggregates and must not be cached by shared
proxies. DataLoader deduplication is scoped to a single request; it is not a
cross-user response cache.

The local profile enables GraphiQL and schema introspection. The production
profile disables both and suppresses servlet exception messages/stack traces.
Resolver errors retain stable `extensions.code` values and redact infrastructure
details. The production-policy test verifies that introspection is actually
rejected by the executable schema, rather than checking configuration alone.

## Limits

Configure `omp.graphql.protection` in application configuration:

| Property | Default | Behavior |
| --- | --- | --- |
| `max-depth` | 20 | Reject deeper operations, including fragment selections; permits standard development introspection |
| `max-complexity` | 2000 | Weighted field cost; maximum configurable value 100000 |
| `max-request-bytes` | 65536 | Bound POST bodies before parsing, including chunked bodies; configurable up to 10 MiB |
| `requests-per-window` | 120 | Maximum GraphQL HTTP attempts per identity per replica |
| `window` | 1m | Fixed window beginning with an identity's first request |
| `max-identities` | 10000 | Bound active rate-limit records; new identities are rejected when full |
| `timeout` | 5s | Execution response budget |

Complexity includes aliases and resolved variable values. Each field costs one
plus its children's cost. Page sizes and nested order limits multiply that cost;
omitted pagination uses 20. Scores saturate above the rejection threshold to
prevent integer overflow from bypassing the budget. These are conservative
estimates, not measured database or CPU costs.

The rate key uses the verified issuer and subject, so rotating a token does not
reset the allowance. Active identities are not evicted to make room for new
ones. Expired records are reclaimed under capacity pressure at most once per
second (or once per configured window if shorter), avoiding a full cache scan
on every rejected identity. Limits are local
to each replica; global ingress quotas and coordinated limits remain separate
backlog work. GET query strings are subject to the HTTP server's URI/header
limits; the configurable body bound applies to POST requests.

Depth and complexity errors use `QUERY_DEPTH_LIMIT` and
`QUERY_COMPLEXITY_LIMIT`. Body/rate rejections return HTTP 413/429 with
`REQUEST_TOO_LARGE`/`RATE_LIMITED`. Expired response budgets return a GraphQL
`QUERY_TIMEOUT` error and cancel the response chain. Other GraphQL error HTTP
statuses follow Spring GraphQL's execution/validation semantics.

Timeout does not undo a mutation or guarantee cancellation of a provider call
already running. Use mutation idempotency keys and re-read state after an
uncertain outcome. Default Feign connect/read timeouts are 1s/3s; the supported
`spring.cloud.openfeign` namespace is used. Resolver execution uses a bounded
pool (8 core threads, 32 maximum, 128 queued tasks). Performance/overload SLOs
and multi-replica enforcement have not been measured.

Regression tests exercise fragments, aliases, large pagination costs, request
body limits, identity isolation, capacity, response cancellation, authenticated
HTTP requests, a slow downstream HTTP server, and production introspection.
