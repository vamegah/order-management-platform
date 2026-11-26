# API security review

The machine-readable [inventory](api-review.json) covers every executable endpoint class, GraphQL resolver, RPC implementation, SOAP endpoint and WebSocket registration. `python scripts/check_security_inventory.py` rejects additions, removals or source changes until their review and linked regression scope are updated. Static inventory is review evidence; it does not substitute for executed negative tests.

| OWASP 2023 category | Review and enforced control | Required executable evidence |
| --- | --- | --- |
| API1 object authorization | Canonical verified customer claim, persisted order owner, subscription owner and private STOMP destination; no display-name authorization | Customer/order ownership tests, public API owner/foreign matrix, WebSocket destination tests |
| API2 authentication | JWT issuer/signature/expiry, five-minute STOMP lifetime, workload mTLS identity and WS-Security timestamp/nonce; no forwarded identity headers | Public API, gRPC and SOAP deployed rejection matrices |
| API3 property authorization | Input DTO allowlists, administrator aggregate writes, version-checked repair plans; clients cannot set state, monetary amounts or durable references via response models | HTTP binding tests, foreign address rejection, repair/checkout correlation tests |
| API4 resource consumption | Fixed request bytes, depth/complexity, pagination/batches/streams/deadlines, bounded executors and shared Redis quotas; ingress limit before parsing | Quota concurrency/outage tests, GraphQL protection, stream/bulkhead tests and isolated load suite |
| API5 function authorization | Administrator repair/refund/bank/replay; separate workload scopes; management kept off public routers | Negative REST/RPC roles, reconciliation and replay denial tests |
| API6 sensitive flows | Scoped durable idempotency and atomic stock/payment invariants; expensive-operation cost class | Concurrency, lost reply/process death, partial refund and repair admission tests |
| API7 SSRF | HTTPS destination registration and connection-time address validation, DNS pinning with hostname checks, no redirects or mixed/internal addresses | Webhook URL/DNS/redirect rejection tests and deployed fixture tests |
| API8 misconfiguration | Mounted configtree secrets, private management/exporters, strict transport, safe logging, bounded telemetry, release deployment guard | Resolved Compose policy, secret renderer, TLS/receiver and release tampering tests |
| API9 inventory | Versioned OpenAPI/GraphQL/protobuf/WSDL/event/client contracts, executable API inventory; test simulator excluded from release | Exact-base compatibility checks and release manifest policy |
| API10 unsafe upstream consumption | Validate exact reservation/payment/warehouse correlation and bank result/fault structure; bounded response parsing; transport retries classified | Checkout malformed/foreign reply regressions, banking fault tests, provider/webhook contract tests |

Review scope includes health and metrics: health exposes no credentials; management scraping uses private connectivity or a verified bearer identity. Exporter roles receive monitoring privileges and aggregate views rather than access to business payloads. Recovery and load controls are restricted to explicitly isolated projects.

Findings and remediation links are in the JSON inventory. A passing review requires all linked suites and Section 7 operational acceptance on the same source revision. Production alerts and promotion remain separate recorded operations when a production target is provisioned.
