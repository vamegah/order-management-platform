# Implementation plan for TASKS.md sections 1-7

> **Status:** In progress; implementation and acceptance remain tracked in TASKS.md
> **Last reviewed:** 2026-11-09

Deliver the **33 unchecked items** in [TASKS.md](../TASKS.md), following its section
order. Five other items in these sections are already checked; preserve their
behavior and regression coverage. The IDs below identify existing backlog items,
not additional completion requirements. Section 8 documentation and final release
acceptance remain a separate final milestone.

## Starting point

The accepted main baseline is `7fc599d`, the approved merge of
[PR #17](https://github.com/vamegah/order-management-platform/pull/17).
[Its main workflow](https://github.com/vamegah/order-management-platform/actions/runs/42652620669)
passes all twenty jobs, 1,207 reactor cases and 25 deployed service/client cases.
The exact source, reports and merge/protection records are archived in
`coverage/reservation-reference-census-main-ci-evidence.zip` and JSON. Supervised
deployed recovery and the historical census are accepted prerequisites. The next
batch implements the maintainer-approved historical terminal exception in 1.1.
Subsequent publication and merge actions follow the existing scoped approval agreement.

Use the latest source-bound CI evidence and the dated continuations in the
[implementation report](task-implementation-report.md). Its early summaries and
some TASKS.md continuation notes describe superseded checkpoints. Audit evidence
before rebuilding working features or reopening accepted fixes.

## Delivery order and completion gates

| Stage | Open items | Main deliverable | Gate before proceeding |
| --- | ---: | --- | --- |
| 1. Core lifecycle | 4 | Durable references, compensation, coordinated expiry and safe transitions | Real deployed failures recover with conserved inventory and correct payment outcomes |
| 2. Public APIs | 2 | Published OpenAPI and external REST/GraphQL compatibility tests | Contract drift, authorization, pagination and errors checked in required CI |
| 3. gRPC | 4 | All streaming patterns, supported replacement behavior and rotating mTLS | Complete RPC coverage matrix and executed transport/replacement tests |
| 4. Events and WebSockets | 5 | Versioned events, complete outbox coverage, quarantine/replay and secure real-time delivery | Crash/replay/ownership tests and measured 1,000-client run |
| 5. Payments and webhooks | 6 | Real simulator integration, tokenized payments/SCA, asynchronous refunds and secure delivery | Provider outcome, signature, SSRF, recovery and load scenarios pass |
| 6. SOAP | 5 | Deployed secured banking flow, stable faults and safe resilience | Trusted-TLS journey, duplicate-transfer prevention and contract gates pass |
| 7. Operations and release | 7 | Coordinated quotas, security review, telemetry, recovery drills and signed releases | Executed operational evidence and reproducible promotion/rollback |

Finish each stage's acceptance before moving to the next feature stage. Bring
forward a supporting dependency when necessary: payment fencing belongs with
stage 1, contract checks accompany every API change, and workload definitions and
basic telemetry precede the stage 4/5 load runs. Record these dependencies against
their original items; they do not establish completion of a later section.

Each implementation batch includes production code where needed, migration and
compatibility handling, meaningful regressions, deployment inputs, and operator
documentation. Prefer batches that close a backlog item or a named prerequisite
with a fixed remaining scope. Test-only changes are appropriate when the existing
behavior already satisfies the item and executed evidence is the missing piece.

An item closes when its original acceptance criteria are demonstrated, required
CI passes for the reviewed revision, the approved merge passes main verification,
and TASKS.md links to that evidence. A partial milestone leaves the parent open
with a specific remaining requirement. Preserve coverage policies and reject
missing/skipped required suites. Record unresolved external dependencies
explicitly rather than substituting mocked evidence for deployed acceptance.

## 1. Finish core order lifecycle

Primary areas: `order-service`, `inventory-service`, `payment-service`,
`shared/proto`, `integration-tests`, recovery Compose inputs and CI.
Reuse the existing journal, cleanup/fulfillment queues, leases and scoped identity.

### 1.1 Persist inventory reservation IDs on orders

- **Implement:** Audit current checkout/migrations and add a bounded, read-only
  historical census of missing or conflicting line references. Extend discovery
  and the existing audited binding procedure only where needed. Resolve each
  fixture through verified order/SKU/warehouse/quantity ownership; reject
  ambiguous bindings. Classify irrecoverable historical rows explicitly and
  prevent them from entering an accepted active state. Apply additional database
  constraints only after repair and upgrade validation.
- **Close when:** Fresh deployed checkout exposes durable, distinct line
  references after restart. A pre-migration database upgrades safely; every
  active and future accepted order line has its verified durable reference.
  On 2026-11-09 the maintainer explicitly approved audited exceptions for
  historical DELIVERED/CANCELLED lines with no recoverable reservation. Such
  exceptions require an administrator attestation and retained evidence; they
  do not fabricate holds, discard rows or waive active/future reference integrity.
  All other unresolvable accepted lines keep this item open.
  Stale/concurrent repair requests preserve unrelated state.

### 1.2 Release reservations during cancellation and compensation

- **Implement:** Complete the deployed cancellation path through the actual
  durable queue, inventory and payment services. Preserve idempotency after lost
  acknowledgments and restarts. Add durable compensation for already committed
  inventory when fulfillment fails partway; releasing an active hold cannot
  reverse a committed stock movement. Fence payment refund eligibility against
  fulfillment in the payment authority and expose pending/attention outcomes.
- **Close when:** Repeated/concurrent cancellation, downstream outages and
  worker death converge without duplicate release, restock, refund or history.
  Multi-line partial fulfillment conserves stock and produces the documented
  payment outcome. No shipment completes against a concurrently refunded payment.

### 1.3 Implement expiry and reconciliation jobs

- **Implement:** First deploy the scheduled Boot worker with a supervised
  least-privilege projector, private token directory and real JWT/mTLS. Exercise
  token replacement, order container restart and inventory/payment restart.
  Then implement accepted-order expiry coordination: inventory atomically
  resolves expire-versus-commit; order reconciliation durably cancels or
  compensates an order whose required hold expired, including payment cleanup.
  Persist retry/attention state, bounded batches, lag metrics and audited retries.
  Define recovery-record retention against an explicit late-write/idempotency
  horizon; retain records while that horizon is unbounded.
- **Close when:** Scheduler-driven deployed recovery handles failures before and
  after each reserve/payment/local-acceptance/compensation boundary. Tests use
  real deadlines and database clocks, with no manual worker ticks or timestamp
  edits. Accepted-order expiry races never ship missing inventory or strand a
  charge. Enable expiry only in an explicit acceptance overlay until this gate
  passes; keep the default deployment policy deliberate and documented.

### 1.5 Define and test order state transitions

- **Implement:** Extend the [state contract](order-state-contract.md) into one
  transition matrix covering order, payment, reservation, fulfillment and refund
  states. Define pending compensation, permanent failure and operator recovery.
  Implement missing transition/fencing rules and compatible REST/GraphQL/gRPC
  representations. Exercise owner/admin/foreign callers and concurrent commands
  through real deployed boundaries.
- **Close when:** Valid paths succeed; every forbidden transition preserves
  state and returns the documented problem/gRPC status. Tests cover refund
  versus shipment, cancellation versus commit/expiry, partial fulfillment,
  duplicate requests and failed compensation. Stage 5 extends this same matrix
  with provider SCA and delayed outcomes rather than creating a second contract.

**Suggested batches:** deployed scheduled recovery; historical repair/reference
closure; coordinated expiry and compensation/fencing; complete transition matrix
and stage acceptance. Item 1.4 (immutable public order lines) remains accepted.

## 2. Complete REST and GraphQL public APIs

Primary areas: public service controllers, `graphql-gateway`, `contracts`,
`integration-tests`, contract tooling and `.github/workflows/verify.yml`.

### 2.1 Publish versioned OpenAPI contracts

- **Implement:** Check in OpenAPI 3.1 contracts under `contracts/openapi/v1` for
  product, customer, order, webhook management and the banking facade. Include
  payment/refund HTTP operations and provider callback boundaries in the API
  inventory so exposed routes cannot escape review. Define schemas, security,
  pagination, idempotency, pending responses and stable problem examples.
  Add structural validation, implementation/schema comparison and exact PR-base
  compatibility checks. Keep documented exceptions explicit.
- **Close when:** CI detects undeclared routes and incompatible schema changes,
  including changes hidden by updating fixtures together. Authenticated examples
  execute through Traefik against packaged services; negative examples return
  the declared errors. Later payment/SOAP work updates these contracts in its PR.

### 2.5 Add external API contract tests

- **Implement:** Add consumer-driven REST tests and a checked-in GraphQL schema
  baseline with exact PR-base change detection. Cover representative queries,
  mutations, batching, pagination boundaries, nullable/error responses, owner
  isolation and production query policy. Generate or compile a consumer against
  the published API artifacts and validate its requests/responses externally.
- **Close when:** Required CI fails intentional breaking REST and GraphQL
  changes, accepts additive evolution under the documented policy, and executes
  authenticated/unauthenticated/foreign-user cases. Existing batch and GraphQL
  protection/policy items 2.2-2.4 remain accepted.

## 3. Productionize gRPC

Primary areas: `shared/proto`, `shared/grpc`, inventory/payment handlers and
clients, TLS tooling, production topology and deployed contract suites.

### 3.1 Validate all gRPC patterns end to end

- **Implement:** Derive an RPC inventory from descriptors and track positive,
  business-error, authorization, deadline and cancellation coverage per RPC.
  Current protos provide unary, client-streaming and server-streaming APIs;
  add an additive bidirectional stock-watch API with bounded watch/unwatch
  commands, initial snapshots, request correlation and explicit overflow policy
  to satisfy the remaining pattern. Apply manual flow control, message/queue
  limits and cancellation cleanup to all streams.
- **Close when:** Containerized JWT/mTLS tests cover every RPC and all four
  patterns. Slow readers, invalid messages, early cancellation and expired
  deadlines leave bounded memory and no leaked subscribers/tasks. Descriptor
  coverage checks reject a newly added RPC with missing required cases.

### 3.2 Make stream fan-out multi-replica safe

- **Implement:** Complete the already selected supported topology: one inventory
  and one payment replica, enforced after all deployment overrides. Test
  process/container replacement, reconnect/resubscribe and canonical-state
  reconciliation. Apply the same policy to the new bidirectional watcher.
  Notification replicas are tested independently in stage 4.
- **Close when:** Forbidden scaling/overlap configurations fail CI; a subscriber
  reconnects after replacement and converges to authoritative state, including
  changes made while disconnected. Document the service interruption and absence
  of durable stream replay. Cross-replica inventory/payment fan-out is a future
  topology change, not required by the existing single-replica acceptance option.

### 3.3 Harden proto evolution

- **Implement:** Audit the existing descriptor, exact-base and generated-Java
  gates against accepted CI artifacts. Complete any missing ownership enforcement,
  field/enum reservation checks, client verification and published version policy.
  Add regressions for uncovered incompatibilities rather than replacing a
  working compatibility tool solely to introduce buf.
- **Close when:** Required protected-branch CI rejects incompatible fields,
  reservations, RPC types/streaming changes and fixture replacement bypasses;
  old generated consumers still work. Link executed evidence to close the stale
  continuation notes in TASKS.md.

### 3.4 Harden internal transport

- **Implement:** Externalize trust/identity provisioning and implement overlapping
  trust-bundle rotation plus certificate/channel reload. The single-replica
  topology requires reload without stopping its service to meet rotation
  acceptance. Add controlled credential expiry and least-privilege checks.
- **Close when:** Plaintext, absent/wrong-CA/expired client certificates, wrong
  server identity and invalid bearer callers are rejected. Continuous authorized
  calls succeed through leaf and CA rotation, old trust is retired, streams
  reconnect safely where required, and no secret is retained in logs/artifacts.

## 4. Make events and WebSockets durable and secure

Primary areas: `shared/events`, every event producer/consumer,
`notification-service`, Kafka/Redis topology, `clients/notifications` and load tools.

### 4.1 Standardize event contracts

- **Implement:** Publish versioned envelope/payload schemas and fixtures under
  `contracts/events`. Specify stable event IDs, aggregate IDs/versions, ordering,
  trace context, timestamps, aliases, deduplication and replay semantics. Add
  producer/old-new-consumer compatibility tests and route malformed/unsupported
  events into the quarantine defined in 4.3.
- **Close when:** Additive old/new producer-consumer combinations interoperate;
  incompatible changes fail CI; invalid events retain safe diagnostic metadata
  and cannot mutate business state.

### 4.2 Finish transactional-outbox adoption

- **Implement:** Build a producer-to-state-change inventory, covering all
  emitting services. Replace any direct/after-commit publishing on durable paths
  with same-transaction outbox writes. Complete broker-acknowledged draining,
  lease/retry/retention rules, oldest-pending metrics and audited replay actions.
- **Close when:** Fault injection at database commit, Kafka acknowledgment and
  drainer restart proves no lost committed event. Redelivery is tolerated through
  consumer deduplication; unpublished records remain recoverable. Every producer
  in the inventory has rollback and recovery evidence.

### 4.3 Add consumer DLQ and replay operations

- **Implement:** Use bounded retries with explicit transient/permanent
  classification, durable quarantine, event/source/offset metadata and redacted
  failure reasons. Make database side effects and duplicate detection atomic;
  acknowledge the broker only after durable handling. Add authorized audited
  list/inspect/replay operations with concurrency fencing and retention controls.
- **Close when:** Poison events leave a partition able to process later valid
  events. Broker/database outages do not lose quarantined work. Concurrent and
  repeated replay neither duplicate effects nor bypass ownership/authorization.

### 4.4 Complete WebSocket authorization

- **Implement:** Finish deployed order/payment ownership verification, bounded
  identity-aware lookups, private routing and subscription cleanup. Close expired
  sessions on a schedule even when idle. Define how revocation is enforced and
  bound its delay; invalidate cached ownership on identity/resource changes.
- **Close when:** Real-issuer tests deny foreign and expired subscriptions,
  isolate users across two notification replicas, and verify unsubscribe,
  disconnect, token refresh and restart behavior without leaked subscriptions.
  The accepted TypeScript client (4.5) continues to converge through state reads.

### 4.6 Load-test real-time delivery

- **Implement:** Add a reproducible k6/WebSocket harness with at least 1,000
  concurrent clients, two notification replicas, slow readers, reconnect bursts
  and replica/Redis disruption. Define the hardware, event rate, duration and
  latency/convergence objectives before measuring. Distinguish transient live
  notifications from durable canonical state and business events.
- **Close when:** A sustained run and recovery run report throughput, latency,
  gaps, duplicates, convergence time and resource use against the declared
  objectives. No unauthorized delivery occurs; overload stays bounded. Any unmet
  objective has prioritized remediation and a rerun before its acceptance claim.

## 5. Complete payment and webhook integrations

Primary areas: `payment-service`, `payment-provider-simulator`, notification
webhook workers/management, payment contracts, Compose and provider e2e suites.

### 5.1 Deploy the payment-provider simulator

- **Implement:** Turn the existing callback simulator into a complete deterministic
  provider fixture for charge, refund and lookup paths, with correlated signed
  callbacks, delay/duplicate/out-of-order/failure controls and idempotency.
  Connect payment-service to it in an isolated integration topology, reusing
  production provider parsing. Keep simulator controls confined to dev/test.
- **Close when:** A fresh Compose deployment completes charge and delayed-refund
  journeys through payment-service, callback verification, outbox and subscriber
  delivery. Restart/retry scenarios preserve provider identities and outcomes.

### 5.2 Replace Stripe test-card placeholder behavior

- **Implement:** Remove the hard-coded `pm_card_visa` request. Accept tokenized
  provider payment methods, bind them to the authorized intent, and model
  action-required/SCA with a safe client next-action response and webhook-driven
  finalization. Preserve provider/local idempotency and redact credentials.
- **Close when:** Success, decline, SCA success/failure, timeout and replay are
  covered without raw card handling. Payment and order state remain consistent
  while action is pending. Deterministic provider tests run in CI; actual Stripe
  test-mode acceptance is recorded separately using securely supplied credentials.

### 5.3 Make refunds asynchronous and webhook-driven

- **Implement:** Extend the existing durable intent, fenced dispatch, GET
  reconciliation and audited repair paths for remaining provider/legacy gaps.
  Drive pending/completed/failed outcomes through deployed signed callbacks;
  handle missing, duplicated and out-of-order outcomes. Reuse stage 1's
  shipment/refund fence and compensation contract.
- **Close when:** Delayed callbacks, worker/provider restart, unknown references,
  concurrent partial refunds and permanent failures reconcile without duplicate
  refunds or exceeding the captured amount. Backoff, attention state and operator
  recovery remain visible and safe across restarts.

### 5.4 Finish inbound webhook security

- **Implement:** Complete each supported provider's raw-byte signature/key
  rotation, timestamp tolerance, durable replay policy, body limits and bounded
  acknowledgment path. Persist verified work before acknowledgment and process
  asynchronously. Apply network allowlists only where the provider supports a
  reliable policy; signatures remain required. Emit redacted rejection metrics.
- **Close when:** Forged, tampered, stale, oversized and cross-provider events
  are rejected. Valid duplicate provider retries receive the documented safe
  acknowledgment with no repeated effect. Controlled load verifies quick durable
  acknowledgment and recovery after database unavailability.

### 5.5 Harden outbound webhook delivery

- **Implement:** Validate registration and revalidate resolved destinations at
  dispatch; prevent DNS rebinding and connections to private, loopback,
  link-local/metadata and forbidden IPv4/IPv6 addresses. Pin the validated address
  while preserving TLS hostname checks, and define a restrictive redirect policy.
  Complete versioned payloads, overlapping signing-key rotation, bounded retries,
  Retry-After handling, retention and audited replay authorization.
- **Close when:** Controlled DNS/redirect tests cannot escape destination policy.
  Subscriber failures and lost acknowledgments retain one delivery identity and
  observable attempt history. Rotation and concurrent DLQ replay work without
  exposing signing secrets or duplicating business actions.

### 5.6 Load-test webhook pipelines

- **Implement:** Reuse the versioned load/SLO profile for ingress, outbox,
  dispatch, slow subscribers, circuit breakers and large DLQ replay. Exercise
  healthy and failed provider/subscriber conditions with bounded workers.
- **Close when:** Measured acknowledgment, delivery, backlog age, drain rate and
  recovery time meet published objectives or lead to explicit prioritized fixes.
  Durable events survive the fault windows and unauthorized replay remains denied.

## 6. Complete SOAP boundary

Primary areas: `legacy-bank-service`, `legacy-banking-adapter`, SOAP/OpenAPI
contracts, banking infrastructure, dashboards and deployed acceptance suites.

### 6.1 Deploy the banking adapter

- **Implement:** Validate existing Docker/Compose/routing/health/scrape inputs
  from a fresh environment, add the missing banking dashboard and secure facade
  access policy, and run REST-to-adapter-to-bank journeys through Traefik.
- **Close when:** Packaged services complete transfer/balance/fault scenarios
  with authenticated facade access and useful health/metrics. Deployment has no
  dependency on locally generated classes or an IDE-built artifact.

### 6.2 Finish WS-Security and TLS configuration

- **Implement:** Supply UsernameToken credentials and trusted TLS material through
  external secret mounts, enforce timestamps/replay policy, and implement a
  documented credential/truststore rotation procedure with overlap where needed.
- **Close when:** Missing/bad/expired/replayed credentials and untrusted TLS are
  rejected. Trusted secured traffic succeeds before and after the executed
  rotation exercise; logs, traces and artifacts contain no passwords or keys.

### 6.3 Define REST/SOAP error contracts

- **Implement:** Complete the existing structured fault mapping and published
  OpenAPI problem schemas for validation, insufficient funds, authorization,
  timeout and backend failures. Keep public codes stable and details redacted.
- **Close when:** Real adapter/bank tests exercise each fault and its deterministic
  HTTP status, problem type and safe body. Backend exception text and SOAP
  implementation details cannot leak through alternative error paths.

### 6.4 Validate resilience

- **Implement:** Add deterministic backend failure/latency controls and verify
  circuit-breaker open/half-open/closed behavior and bounded timeouts. Replace
  the adapter's in-memory transfer idempotency cache with durable request/result
  records and end-to-end backend duplicate detection before retrying ambiguous
  transfers. Classify retryable transport errors separately from business faults.
- **Close when:** Concurrent duplicate requests, adapter/bank restarts and a
  lost transfer reply never duplicate a transfer. Failure injection proves
  bounded work, useful fallback errors and breaker/retry metrics without a
  cascading failure.

### 6.5 Add WSDL drift protection

- **Implement:** Audit the existing frozen WSDL/XSD, exact-base comparison,
  published-schema parity, JAXB regeneration and Java boundary gates. Repair
  only missing enforcement/evidence; keep incompatible v1 changes blocked.
- **Close when:** Required CI demonstrates fresh generated artifacts and rejects
  incompatible WSDL/XSD changes even when all head fixtures change together.
  Link the accepted repository runs and ownership/version policy to TASKS.md.

## 7. Security, operations and release readiness

Primary areas: shared security, ingress, all public endpoints, infrastructure,
performance/chaos tooling, runbooks and release workflows.

### 7.1 Add ingress and API rate limits

- **Implement:** Combine coarse ingress protection with verified-identity quotas
  in services. Use shared atomic counters/token buckets for replica-wide limits,
  bounded keys/expiry, endpoint cost classes and explicit store-failure behavior.
  Cover REST, GraphQL, WebSocket handshake/SUBSCRIBE, callbacks and expensive
  administrative operations; preserve legitimate provider retry semantics.
- **Close when:** Multi-replica burst tests demonstrate consistent quotas and
  recovery, stable HTTP/GraphQL/STOMP errors, Retry-After where applicable and
  low-cardinality metrics. Spoofed forwarding headers cannot bypass limits.

### 7.2 Conduct an OWASP API Top 10 review

- **Implement:** Build an endpoint/RPC/topic/management inventory mapped to the
  current OWASP API categories and trust boundaries. Review object/function
  authorization, writable properties, resource bounds, token handling, SSRF,
  unsafe upstream responses and obsolete/debug endpoints. Track each finding
  with severity, remediation and an executable negative test.
- **Close when:** Every public endpoint class has review evidence, all release
  blocking findings are fixed, and accepted residual risks have an explicit
  maintainer disposition. Repeat the review for new routes introduced above.

### 7.3 Add Alertmanager and SLO-based alerts

- **Implement:** Complete existing rules with validated service indicators,
  error-budget burn windows, lag/saturation/certificate alerts and useful runbook
  annotations. Configure real receiver secrets, routing, inhibition and bounded
  silences; use a local recording receiver for automated tests.
- **Close when:** Rule tests pass and controlled failures produce actual
  fire/route/inhibit/resolve receipts at the intended configured receiver.
  Production receiver delivery has its own recorded test; configuration alone
  does not close this item.

### 7.4 Complete observability

- **Implement:** Add least-privilege Postgres/Redis/Kafka exporters, service
  RED/USE dashboards and trace-correlated exemplars. Activate bounded OTel tail
  sampling for errors/slow traces plus sampled normal traffic; preserve trace
  affinity if collectors scale. Propagate context through HTTP/GraphQL, gRPC,
  outbox/Kafka, notifications/webhooks and the adapter's SOAP client.
- **Close when:** An automated journey resolves metrics/exemplars to a trace
  containing the expected cross-protocol spans and safe structured logs.
  Separate linked banking journeys cover SOAP where it is not part of checkout.
  Sampling/collector failure tests preserve application availability and bound
  telemetry memory/cardinality without leaking sensitive data.

### 7.5 Create performance and chaos suites

- **Implement:** Consolidate stage 4/5 harnesses into versioned k6 workloads and
  deterministic fault scenarios for Postgres, Kafka, Redis, provider, SOAP and
  downstream HTTP/gRPC. Add mixed checkout/refund traffic, soak tests and recovery
  assertions, including stock/payment/event conservation.
- **Close when:** Reproducible runs record hardware, workload, warm-up, duration,
  raw measurements and achieved SLOs. Every missed objective creates prioritized
  remediation. Release gates distinguish a reported benchmark from a passing
  objective; destructive scenarios operate only on isolated test environments.

### 7.6 Operationalize backups and recovery

- **Implement:** Complete executable Postgres backup/PITR restore, Kafka/DLQ
  recovery, outbox replay, credential/certificate rotation and deployment rollback
  runbooks. Restore into separate infrastructure and verify order/payment/
  inventory references, event deduplication and unfinished recovery queues.
- **Close when:** Each runbook has an executed dry-run record, measured RPO/RTO,
  verification queries and rollback/cutover criteria. Recovery resumes legitimate
  pending work without replaying external charges/refunds/transfers unsafely.

### 7.7 Harden supply chain and deployment

- **Implement:** Extend the existing dependency policy/SBOM/image builds with
  digest-pinned deployment manifests, image scanning, signed images/SBOMs and
  provenance verification. Use an external secret provider or mounted runtime
  secrets; restrict CI/release credentials. Promote the same immutable artifact
  through test/staging/production using protected environment approvals and
  compatible migration/rollback rules.
- **Close when:** A tagged release can be reproduced, verified and promoted by
  digest; unsigned/tampered artifacts are rejected. A staging exercise demonstrates
  secret delivery, approval enforcement and rollback without rebuilding images
  or applying an unsafe reverse database migration.

## Verification, inputs and progress reporting

Use the current required workflow as the baseline: focused tests/static checks
for the change, full reactor, packaged/deployed journeys, exact-base coverage and
contracts, dependency policy, images and identity checks. Add each new acceptance
suite to required discovery so a skipped or accidentally excluded suite fails CI.
Archive source revision/tree, run/attempt, JUnit counts, logs, public deployment
inputs and hashes; exclude credentials and private keys. Run main verification
after approved merges.

Before the first load run, check in a workload/SLO profile specifying hardware,
rates, concurrency, duration, percentiles, tolerated notification gaps, state
convergence and backlog recovery objectives. Define RPO/RTO before restore drills.
These become measured engineering targets; release approval of the final values
belongs to section 8. Preserve correctness invariants regardless of throughput:
no unauthorized delivery, duplicate monetary/stock effects or lost durable work.

External inputs needed at their corresponding stage are a secured Stripe
test-mode account, a representative isolated load/recovery environment, actual
alert receivers, a backup store, signing identity and environment approvers.
Continue implementation with deterministic fixtures while provisioning these;
keep any affected real-provider/operational acceptance explicitly open until run.

For each batch, report: backlog IDs addressed, implemented behavior, executed
evidence, remaining acceptance and the next batch. Update this plan by replacing
superseded decisions, and keep historical results in the implementation report.
After all 33 items close, execute section 8's consolidated documentation, approved
release checklist and fresh full-stack acceptance.
