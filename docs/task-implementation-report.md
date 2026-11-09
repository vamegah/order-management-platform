# TASKS.md implementation report

Updated: 2026-11-08. Feature and release acceptance remain incomplete.

The accepted main baseline is `a3e4b85` (PR #16, merged by the repository owner).
Its complete protected workflow passes all twenty jobs, 1,196 reactor cases and
25 deployed service/client cases. Exact source, reports and merge/protection
records are retained in the supervised recovery CI evidence archives documented
in the latest acceptance section.

The current local follow-up implements a bounded administrator census of missing
and shared reservation references. It remains separate from accepted PR #16;
local verification prepares it for publication review. Complete hosted CI and
main verification remain required before acceptance.

Sections 1-7 retain 33 open items. The [implementation plan](task-implementation-plan.md)
defines their delivery order and completion criteria; [TASKS.md](../TASKS.md)
records acceptance. Earlier sections below are historical checkpoints, including
superseded failure/environment reports. Use the latest dated evidence for each
scope. Production security, performance, recovery and release requirements remain
open.

## Implemented changes

- Windows Maven wrapper handles a Maven cache directory with no link target.
- Platform tests now inherit the reactor parent and use the reactor service coordinates.
- Restored missing customer status, shared value-object imports, client DTOs, test dependencies,
  and test setup; repaired product generic specifications, category mapping, duplicate error handler,
  and domain timestamp callbacks. Order architecture checks now import the order package.
- Restored gateway client request contracts and typed errors. DataLoader registration uses
  existing DTOs and retains downstream failures. Added all query/mutation resolvers, cross-service
  field resolvers, scalars, bounded pagination, role restrictions, and redacted GraphQL errors.
  Removed the gateway's dependency on the order-service implementation and a test-only production
  schema extension. Added bounded product/customer batch endpoints with ordered,
  deduplicated results and customer ownership restrictions; DataLoaders now use
  these endpoints in batches of at most 100 IDs. See [the contract](batch-read-contract.md).
- Corrected gRPC interceptor registration and the supported bearer-token authentication reader.
  Added the missing additive `BulkReserveItem.order_id` field used by the server.
- Order checkout persists reservation IDs with order lines in one transaction. Cancellation
  retries release for all recorded reservations, tolerates individual cleanup failures, and
  does not duplicate cancellation history. Opt-in cleanup/recovery workers and their deployment
  limits are documented in [cleanup operations](order-cleanup.md).
- Removed the order-line ping endpoint and documented immutable public order lines.
- Repaired bank WS-Security bean wiring and timestamp validation configuration.
- Repaired adapter WSDL generation, generated-package imports, HTTP sender, and incomplete REST
  controller code. HTTPS uses JVM trust and hostname verification.
- Bank faults use the existing XSD's namespaced code/message detail. Adapter mapping uses exact
  structured codes and redacts transport messages. See [the contract](soap-fault-contract.md).
- Reconciled payment domain/provider interfaces, gRPC converters, status names, refund accounting,
  exact currency minor-unit conversion, and charge/refund idempotency checks. Pending refunds
  retry the provider after temporary errors, while completed refunds return without reapplying.
- Added durable provider-event deduplication with an atomic PostgreSQL insert, payment lifecycle
  migrations, and the shared transactional outbox. Both REST and gRPC event writers require a
  surrounding transaction. Fixed outbox scheduler parsing for duration configuration.
- Added stateless HTTP JWT security, owner/admin payment read/refund checks, and a required
  customer identity for charges. Webhooks preserve signed raw bytes and enforce a 256 KiB body
  limit, including chunked requests. Hardened signature timestamp arithmetic against overflow.
- Restored notification event reader/registry wiring and repaired notification test dependencies.
- Implemented payment WebSocket ownership reads with caller JWT relay and fixed order UUID
  comparison. Verified `customer_id` claims avoid customer lookups; legacy tokens fall back to
  `/me` for identity resolution. Added `/user/queue/notifications` for customer-scoped order and
  payment events, routed by stable subject rather than display name.
- Subscription IDs now match STOMP IDs scoped to each session. Explicit unsubscribe removes
  only that ID, disconnect removes all session entries, duplicate active IDs are rejected, and
  registry snapshots are immutable copies. Client SEND frames are denied.
- Removed duplicate/deprecated STOMP security registration in favor of the production inbound
  authentication and subscription interceptors. Subsequent expired-session commands are denied.
  Native credentials are removed after CONNECT authentication and stored in a token wrapper
  whose diagnostic representation is redacted; the session JWT takes priority during Feign relay.
- Shared outbox activation now requires the service's outbox repository. Consumer services can
  start without one; disabling background draining preserves producers' transactional writer.
- Added GraphQL depth/weighted complexity instrumentation, bounded HTTP body reads, identity-based
  rate limits with bounded tracking, and execution deadlines. Blocking controllers now execute on
  a bounded pool with explicit caller security-context propagation. HTTP tests verify token relay
  and deadlines against a real delayed downstream server, including normal queries and rejection
  before downstream calls. Complexity scores saturate to prevent arithmetic bypasses.
- Defined full-query-only production policy: reject persisted-query extensions, set no-store
  response caching, disable production introspection/GraphiQL, and redact servlet error details.
  Corrected the supported Feign timeout property namespace. See [the policy](graphql-production-policy.md).
- Repaired platform-test packages and fixtures. Added real Kafka topic/drain helpers and rewrote
  inbound-webhook/outbox tests against the actual payment application and database schema.
- Fixed shared pagination bean naming and service Logback appender selection, which prevented
  application startup even after successful compilation.
- Disabled VS Code Java workspace auto-building to prevent its compiler from overwriting
  Maven output while tests run; Maven remains the workspace build authority.
- Added a PR/default-branch workflow for reactor verification, image builds, and test report artifacts.
  The workflow has not been executed on GitHub; complete verify/coverage acceptance remains pending.
- Removed a misplaced Compose `services.networks` entry and attached the bank to the app network.

## Verified evidence

- GraphQL continuation: 58 gateway checks passed, including authenticated HTTP resource-limit
  rejection, correct JWT relay through asynchronous controller execution, and a 5s response
  deadline against a provider delayed for 10s. Standard development introspection succeeds;
  production introspection is rejected. Existing resolver and architecture checks remain green.
  The reactor-wide pass also ran 7 product/order architecture checks and compiled all 17 modules.
  Evidence: `graphql-final-verification.log`. The final capacity-pressure cleanup check passed its
  3 selected tests and all 17 modules compiled again (`graphql-filter-verification.log`). Combined
  with the WebSocket continuation, this round has 109 distinct passing focused checks; the final
  filter rerun is already included in that count.

- WebSocket continuation: all 17 reactor modules compiled with 44 passing selected checks:
  outbox activation/scheduling 4, targeted payment regressions 4, and notification checks 36.
  The latter include a real local socket exercising production CONNECT authentication, private
  user-queue routing and explicit unsubscribe cleanup. Evidence: `websocket-final-verification.log`.
  This is not a Docker/Kafka/Redis integration acceptance run.

- Reactor-wide `spotless:apply validate` passed, including the restored platform-tests module.
- Earlier focused baseline suites totaled 46 passing tests/checks; these are historical results,
  overlap the latest run, and do not represent the complete suite.
- Adapter: 11 tests passed (7 REST error mappings and 4 SOAP client contract cases).
- Bank simulator: 6 tests passed (4 service tests and 2 SOAP fault serialization tests).
- Product: 13 tests passed (6 service, 4 specification, and 3 architecture checks).
- Customer: 6 service tests passed.
- Focused order suite: 6 tests passed, including reservation association, rejection of unreserved
  lines, mixed currency rejection, invalid transition mapping, and repeat cancellation with a failed release.
- Order architecture: 4 checks passed against the actual order package.
- Reactor-wide production and test-source compilation passed, including platform-tests:
  `./mvnw spotless:apply test-compile`.
- An earlier clean focused regression run passed 107 checks with zero failures, errors or
  skipped selected tests: shared events 3; product service/architecture 11; customer service
  and batch authorization 10; gateway resolvers/schema/architecture 42; payment 21;
  notification event/registry/topic tests 20. Evidence: `implementation-tests.log`.
- The gateway batch regression resolves repeated and distinct product IDs using one HTTP
  request, correctly matching an intentionally reordered downstream response.
- Standalone MVC request tests verify missing and malformed batch IDs return 400 without
  calling the product/customer service. These tests complement, rather than replace, the
  customer method-authorization tests.
- An earlier reactor-wide `spotless:apply test` with only those two request tests selected passed
  all 17 modules, compiling their production and test sources. This brings the latest focused
  evidence to 109 passing checks. Evidence: `final-compilation-tests.log`. The other tests were
  not selected in that final compilation pass; full-suite acceptance is still open.
- Payment coverage includes currency precision, terminal states, partial refunds, mismatched
  idempotency keys, duplicate webhook handling, owner/admin access, temporary refund retry,
  webhook size limits, provider outcomes and signature timestamp bounds.

Commands used:

```sh
./mvnw -pl legacy-bank-service,legacy-banking-adapter clean test
./mvnw -pl order-service -am test -Dtest=OrderServiceTest,OrderTransactionServiceTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw compile
./mvnw spotless:apply validate
./mvnw spotless:apply test -Dtest=ProductBatchRequestTest,CustomerBatchRequestTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl product-service,customer-service,graphql-gateway,payment-service,notification-service -am clean spotless:apply test -Dtest=ProductServiceTest,CustomerServiceTest,CustomerBatchAuthorizationTest,EventRegistryTest,OutboxSchedulingTest,MinorUnitsTest,PaymentLifecycleTest,PaymentIdempotencyTest,RefundRetryTest,PaymentAccessTest,WebhookBodyLimitFilterTest,StripePaymentProviderTest,StripeWebhookVerifierTest,*ResolverTest,SchemaTest,LayeringTest,SubscriptionRegistryTest,TopicTypeTest,EventTypeRegistryTest,EventDeserializationSupportTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Remaining blockers and limitations

Full `verify` acceptance remains unproven. Source compilation now passes, but that does not
establish runtime correctness or coverage compliance. GraphQL subscriptions remain unfinished.
Global ingress quotas and multi-replica limit coordination remain open. Order placed-date filters are
explicitly rejected because the downstream REST client does not yet support them.
Payment refunds remain synchronous;
provider reconciliation, asynchronous completion and replacing the hard-coded Stripe payment
method remain unfinished. Automatic saga reconciliation and broader security/operations work
are still represented by unchecked tasks.

The bundled Keycloak realm does not yet provision the trusted `customer_id` claim required by
payment HTTP authorization. That issuer migration and deployed ownership acceptance remain open.
WebSocket checks do not implement scheduled expiry closure, immediate token revocation, disconnected-message replay or
durable delivery. Redis fan-out and multi-replica behavior remain unverified in this environment.

Docker is unavailable in this environment. Container integration tests, e2e deployment, mTLS
rotation, performance/chaos runs, backup restores, alert delivery, and production promotion
have not been executed. No measured SLOs, successful recovery exercises, or production readiness
are claimed. The remaining unchecked backlog covers substantive unimplemented work, not just
environmental verification.

There is no Git repository metadata in this workspace, so no diff against an original commit,
commit, or pull request was available.


## Inventory expiry and event durability continuation

Added an opt-in bounded reservation expiry worker, a reservation optimistic-lock version migration, and shared transactional-outbox adoption for all inventory reservation lifecycle producers. Release after expiry is an idempotent success; reserve retries reject changed quantities/warehouses and terminal holds. See [reservation expiry operations](reservation-expiry.md).

The full 17-module reactor compiled with the focused expiry tests passing in `inventory-expiry-verification.log`. This is not full-suite verification. Additional focused retry verification is recorded in `inventory-retry-verification.log`.

The expiry job remains disabled by default until checkout/payment reconciliation handles expired holds. Durable order compensation, orphan reconciliation, operator retry actions, PostgreSQL concurrency tests, and failure-injection acceptance remain open. Neither the reconciliation task nor full outbox adoption across services is marked complete.


## Order event durability and cleanup continuation

Order lifecycle publishers now write the shared transactional outbox inside order transactions, including creation. This closes the previous failure path where a post-commit Kafka publication error could compensate an already accepted order. Added Flyway V6 for the orders outbox and V7 for durable cleanup tasks.

Cancellation queues reservation/payment cleanup atomically with state and event changes. Known resources from failed creation are also queued before cleanup attempts. Bounded leased retries retain failures, continue other resources, and refuse cleanup for active orders. Added a method-secured admin retry endpoint and pending/completed/failure metrics. Duplicate SKU lines are rejected before any reservation or payment call; create requests are bounded to 100 lines.

Fixed order-service base YAML indentation and moved JPA, Flyway and JWT properties from the accidental grpc namespace into spring. A configuration test checks these bindings.

Focused verification initially passed 11 tests in `order-cleanup-final-verification.log`; expanded verification passed 28 focused tests (17 order lifecycle/cleanup/authorization/configuration/architecture, 3 product architecture, and 8 gateway architecture) in `order-durability-reactor-verification.log`. This is focused verification, not the full integration suite. Operational details and remaining gaps are in [order cleanup](order-cleanup.md). The reconciliation task stays unchecked: scheduled service-account retries, a complete checkout journal, unknown-resource discovery, database concurrency/crash tests, and business compensation for confirmed payments remain unfinished.


## Checkout journal and autonomous recovery continuation

Added the V8 durable checkout journal before remote mutations and an acceptance fence committed with the order and its outbox event. Leased recovery prevents a stale checkout from accepting after cleanup starts. Additive scope-protected unary inventory/payment lookup RPCs discover IDs lost in timeouts. Explicit write-RPC deadlines now bound each gRPC attempt.

An opt-in worker rereads a rotating mounted service JWT each tick, scans stale/failed checkout attempts, enqueues discovered resources, and retries due cleanup tasks. Previously recovered attempts remain eligible for discovery of late remote commits. No user token is persisted. Normal caller-token forwarding takes precedence over the scoped worker identity. The worker remains disabled in bundled deployment until the issuer identity and projection/refresh mechanism are provisioned.

Focused checks passed 22 tests in `checkout-recovery-focused-verification.log`, including six in-process gRPC tests for scope enforcement, bounded lookups, and stable error codes; three token rotation/failure/discovery tests; and checkout fencing regressions. Expanded verification passed 44 focused tests with all 17 reactor modules successful in `checkout-recovery-reactor-verification.log`. The final conflict-contract regression also passed in `checkout-conflict-verification.log`, for 45 distinct focused tests across these two runs. Added five PostgreSQL integration tests in `CheckoutJournalIT`; these require Docker and are not counted as executed tests. No Docker executable or engine pipe is available in this environment.

See [order cleanup and recovery](order-cleanup.md) for settings, identity requirements, timing, metrics, and integration-test invocation. Remaining acceptance includes real PostgreSQL concurrency/crash execution, deployed JWT/mTLS identity checks, initial audit of pre-journal orphans, retention of repeatedly scanned journal entries, confirmed-payment refund handling, and accepted-order expiry behavior. The section 1 reconciliation task remains unchecked.


## Lifecycle concurrency and creation idempotency continuation

Added the V9 order version migration and a redacted HTTP 409 conflict contract. Checkout-assigned order UUIDs are no longer annotated as database-generated. Full-detail order reads avoid joining both list bags and initialize history inside the transaction. Domain tests cover the complete journey, cancellation before shipment, and rejected transitions preserving state/history.

Payment creation now shares a bounded PostgreSQL advisory transaction lock across REST/gRPC before the idempotency lookup. Matching gRPC replays return the same payment without another event; changed order/customer/amount/currency details are rejected. Removed the attempt to query a winner inside an already aborted unique-constraint transaction. Added exact protobuf money validation, REST storage-precision checks, and provider binding/consistency for charging an existing gRPC intent. Unary payment observer errors now use explicit redacted gRPC status mapping; HTTP payment contention returns 409.

The focused run in `lifecycle-contract-verification.log` passed 47 tests before the final REST provider/precision regressions were added. Added six real PostgreSQL/JPA integration tests in `OrderPersistenceIT` and `PaymentCreationConcurrencyIT`; these are compiled but not executed here because Docker is unavailable. Existing Docker-backed product/customer context tests and the outbound webhook journey were renamed to `*IT` so the complete unit phase can run independently. They remain required by Failsafe during `verify` and CI; none were disabled.

The complete clean reactor unit phase passed 259 tests across 66 suites, with zero failures, errors, or skips and all 17 modules successful (`reactor-unit-verification.log`, `mvn clean spotless:apply test`). See [test phases](test-phases.md), [order state contract](order-state-contract.md), and [payment creation contract](payment-idempotency-contract.md). Payment-backed confirmation, durable inventory commitment during shipping, confirmed-payment refund compensation, and real concurrency/failure-injection acceptance remain open, so the order lifecycle task stays unchecked.


Inventory retry activation was also missing: `@Retryable` was present without `@EnableRetry`. Added production retry configuration and proxy-level tests for three optimistic retries versus a single insufficient-stock rejection. This late change passed all nine inventory tests and six shared-event tests in `inventory-retry-activation-verification.log`, including two new activation tests. Across the clean reactor run and this follow-up, 261 distinct unit/in-process tests passed. Test-only configuration classes used by explicit contexts are marked `@TestConfiguration` so they do not leak mocked repositories or alternate datasources into application component scans.


## Payment-backed order transitions continuation

Confirmation and shipment now read authoritative payment evidence before transitioning. The linked payment must match the payment/order/customer IDs, total and currency, and be fully confirmed with a provider reference. Invalid starting states/missing links fail without a remote call. Remote reads have explicit deadlines and caller-token forwarding, execute outside database transactions, and fail closed. The local transaction rechecks the snapshot version, payment link, and payment/order fingerprint before committing history and the outbox event.

The initial HTTP-to-gRPC contract run exposed an existing problem response mismatch (HTTP 502 with body status 400). Added shared forbidden/gateway problem factories, aligned order problem body statuses with HTTP 403/502, and removed downstream details from public order dependency/access errors. The corrected focused run passed 33 tests in `paid-order-contract-verification.log`. The expanded reactor run passed 59 focused tests with zero failures/errors/skips and all 17 modules successful (`paid-order-reactor-verification.log`), including 26 paid-transition contracts and seven GraphQL order mutation checks.

The new contract fixture uses the real MVC controller/orchestrator/payment client and an in-process gRPC server; its local transaction layer is mocked. It verifies payment-state rejection, identifier/amount/currency mismatches, concurrent local changes, token/deadline forwarding, and redacted errors. Real PostgreSQL and deployed authorization acceptance remain pending. A remote refund can still race the local commit because this is a preflight read, not distributed atomicity. Durable inventory commitment, refund/fulfillment coordination, and full lifecycle journeys remain open; the overall state-transition task is not marked complete. See [the updated state contract](order-state-contract.md).

## CI, quality evidence and executable packaging continuation (2026-10-29)

Expanded the PR/default-branch workflow into independent test/coverage, SpotBugs,
dependency-policy/SBOM and ten-service image jobs. A final `quality-gate` requires
all jobs, including the entire image matrix, to succeed. It rejects skipped or
cancelled prerequisites. Branch protection and actual GitHub execution remain
unverified because this workspace has no repository metadata or remote.

Added maximum-effort, high-confidence SpotBugs checks, a CVSS >= 7.0 failing
Dependency-Check policy, and aggregate CycloneDX 1.6 JSON/XML generation. NVD keys
are read directly from the environment; scanner/update errors fail the job. Only
generated protobuf/JAXB packages are excluded from static analysis. Existing
coverage thresholds and application exclusions were not weakened. See
[quality gates](quality-gates.md) for commands, scope and pending acceptance.

Added a standard-library Python evidence checker that rejects missing suites,
zero-test reports, failures/errors/skips, missing JaCoCo XML and zero measured
production coverage. Eight regression tests cover these failure cases, nested
JUnit reports, pom aggregators and e2e inclusion. Default Failsafe now excludes
the `e2e` tag as documented; `-Pe2e` includes it without filtering out untagged
integration suites. Both effective Maven models were resolved and their actual
filters checked (`profile-selection-verification.log`).

Boot packaging now attaches executable `*-exec.jar` artifacts while retaining
plain jars for reactor/test dependencies. All service Dockerfiles build from the
reactor root with the checked-in wrapper and shared modules; Compose build
contexts were corrected accordingly. Added Dockerfiles for the simulator and
banking adapter and a root `.dockerignore` for local outputs and private material.
These new Dockerfiles do not deploy the services or establish runtime acceptance.
`check_packaging.py` verified the plain application classes, Boot manifests and
actual extraction of all four Docker layers for all ten executable modules
(`packaging-verification.log`). No Docker image was built here.

Added 21 shared-web tests for bounded pagination/sort rejection, stable page/error
contracts, validation fields, trace/correlation cleanup, property binding and
auto-configuration overrides. Shared-web instruction coverage is now 100%, with
its existing 85% check passing (`shared-web-quality-verification.log`).

The simulator had no `webhookScheduler` bean and could not start. Added the
managed scheduler and ten tests covering real HTTP success/failure initiation,
request validation, scheduled signed delivery to an actual local HTTP receiver,
provider/event correlation, serialization failure and delivery-error metrics.
Its existing 80% coverage check passes (97.38% overall instruction coverage in
the generated report). This tests the simulator boundary; it is not a deployed
payment-service webhook journey (`simulator-quality-verification.log`).

Executed evidence:

- The complete reactor unit/packaging phase passed **319 tests across 75 suites**,
  with zero failures, errors or skips and all 17 modules successful:
  `mvn spotless:apply package jacoco:report` in
  `quality-reactor-unit-verification.log`. An initial clean run exposed a simulator
  test compilation issue; it was repaired before the successful reactor rerun.
- SpotBugs passed across all 17 modules with no high-confidence findings
  (`static-analysis-verification.log`). The final simulator scheduler/source
  follow-up also passed (`simulator-static-verification.log`).
- All eight Python evidence-gate regression tests passed; all ten real executable
  jar extraction checks passed.
- Aggregate CycloneDX JSON/XML generation passed after installing the tested
  reactor artifacts (`sbom-artifact-preparation.log`, `sbom-verification.log`).
  The inventory contains 296 components and 297 dependency-graph entries. JSON,
  XML and all dependency references were checked; integration/platform test
  modules and JUnit/Mockito frameworks are excluded. Artifact preparation skipped
  tests/coverage and is not counted as another test run.
- The evidence checker correctly rejected the partial local run because required
  container integration reports are absent (`verification-evidence-audit.log`).
  Workflow YAML and Compose root build contexts/Dockerfile paths were also checked.
- `mvn verify` reached the product container phase and failed six suites because
  Docker is unavailable (`baseline-continuation-verification.log`). This is an
  environment failure, not evidence of flaky tests or full integration acceptance.
- A separate `jacoco:check@check` audit evaluated the existing thresholds against
  the unit-only execution data without rerunning tests (`coverage-gate-audit.log`).
  Shared domain, shared web and the simulator passed. Seven service checks failed:

| Service | JaCoCo reported unit instruction ratio | Required |
|---------|---------------------------------------|----------|
| Product | 0.31 | 0.80 |
| Customer | 0.20 | 0.80 |
| Order | 0.57 | 0.80 |
| GraphQL | 0.79 | 0.80 |
| Inventory | 0.23 | 0.80 |
| Payment | 0.37 | 0.80 |
| Notification | 0.22 | 0.80 |

Container integration execution may add coverage; these unit-only results do not
establish combined coverage compliance. At that point shared events and the
banking modules had no measured policies, and no PR/base coverage comparison was
available. The shared-events policy was added in the subsequent repairs below.

Dependency-Check 13.0.0 downloaded and executed, but its NVD database update failed
with an authentication/API-key error while no `NVD_API_KEY` was configured
(`dependency-data-verification.log`). It correctly returned failure. No completed
vulnerability scan or clean dependency inventory is claimed. Docker/GitHub
execution, e2e fixture completion, coverage remediation and deployment acceptance
remain open. No release-acceptance checkbox was marked complete by this round.

## Shared module repairs

The broader backlog was held while all four shared modules were audited and
repaired. Details and compatibility implications are in
[the shared module audit](shared-module-audit.md).

- Domain now enforces monetary storage bounds through factories and arithmetic,
  handles extreme exponent inputs without huge intermediate allocations, and
  rejects malformed email/phone values that previously passed validation.
- Common web utilities now activate without a tracing bean. Correlation IDs are
  bounded, owned MDC entries are restored on failures, and sort parsing rejects
  empty/duplicate fields while applying the allowlist to defaults.
- Events now round-trips all sixteen canonical record types with validated
  metadata and a class whitelist. Outbox writes require a writable transaction,
  acknowledgement waits are bounded, interrupts are preserved, and legacy
  publishing propagates asynchronous failures. Topic provisioning uses the
  application's KafkaAdmin configuration. Added an 80% instruction coverage gate
  without production-class exclusions.
- Proto tests are enabled. Generation attaches a descriptor-set artifact; a
  frozen v1 baseline checks schema/RPC compatibility, and generated-client tests
  cover streaming descriptors, monetary strings and unknown-field preservation.
  Existing schema field numbers, packages and RPC signatures were preserved.

Final evidence:

- **137 shared tests pass:** domain 37, common web 32, proto 5, events 63.
- All shared coverage gates pass: domain **97.35%** against 90%, common web
  **100%** against 85%, events **90.81%** against 80%. Generated protobuf code
  is verified through compatibility tests.
- Shared-module SpotBugs checks pass (`shared-final-verification.log`); the final
  money follow-up also passes tests, coverage and SpotBugs
  (`shared-domain-final-verification.log`).
- All **17 reactor modules package successfully**, with **408 unit and in-process
  tests**, zero failures, errors or skips (`shared-consumer-regression.log`).

Docker remains unavailable. Real PostgreSQL locking/rollback and Kafka
TLS/SASL/topic-management acceptance remain unverified; no container integration
or deployment acceptance is claimed. Broader backlog tasks remain open.

## Coverage regression and banking boundary continuation

Added a required PR coverage-regression job. It builds the merge revision and
exact PR base separately with the same JaCoCo version and clean unit phase, then
compares exact per-module instruction fractions. Missing evidence, skipped or
failing unit suites, malformed counters, omitted handwritten source classes and
coverage decreases fail the gate. New production modules require at least 80%
unit instruction coverage. Only generated protobuf/JAXB packages are filtered;
existing absolute coverage gates remain unchanged. JSON snapshots and a summary
table provide review evidence. Push/manual runs validate the tooling without a
PR-base comparison. See [the policy and commands](quality-gates.md).

Both banking modules now enforce 80% instruction coverage over all handwritten
production code, excluding only generated JAXB classes. The additional boundary
tests exposed and repaired runtime defects beyond compilation:

- The bank application's incorrect package prevented component scanning of its
  ledger, SOAP endpoints and security configuration.
- The default WS-Security PasswordText check compared the supplied password
  directly with a stored BCrypt hash and rejected valid callers. The custom
  validator now integrates Spring's password encoder, checks user account state
  and rejects unsupported token types while retaining timestamp validation.
- The generic SOAP exception resolver consumed business faults before the
  structured resolver. Explicit precedence now preserves BankingFault details
  over the real HTTP boundary.
- Adapter retry configuration referenced a nonexistent exception class and
  prevented application startup. It now references the actual class.
- Idempotency-key reuse with a changed payload now returns a stable HTTP 409
  problem. The existing cache remains in memory; durable/concurrent idempotency
  and broader resilience acceptance are still open.

The focused clean banking verification passed **25 tests** (bank 11, adapter 14),
both new coverage gates and both SpotBugs checks with no high-confidence
findings (`banking-quality-final-verification.log`). Instruction coverage was
**94.57%** for the bank and **92.01%** for the adapter. Static XML reports were
preserved under `target/banking-quality-evidence` before the subsequent reactor
clean. All **25 Python tooling tests** pass (`coverage-tooling-verification.log`).

The final clean reactor measurement using the CI command passed **416 unit and
in-process tests across all 17 modules**, with zero failures, errors or skips
(`coverage-regression-reactor-verification.log`). The coverage snapshot contains
all **13 production modules**. Pom-packaged aggregator scaffolds are excluded
because Maven does not compile them. The CLI self-comparison passed and an
injected one-instruction regression returned the required failure exit code
(`coverage-comparison-verification.log`). This is local gate validation, not a
comparison of two real Git revisions. All **ten executable service packages**
also passed plain-jar, Boot launcher and actual Docker-layer extraction checks
(`banking-continuation-packaging-verification.log`); no Docker image was built.

The bank tests start the actual application and exercise authenticated transfer,
balance and reversal over a local socket, structured faults, WSDL publication,
and rejection of missing/invalid credentials, unknown users, digest tokens and
missing timestamps. Adapter tests exercise real REST through its production
JAXB/WS-Security configuration with a controlled SOAP transport. See
[banking verification scope](banking-verification.md).

These checks use local HTTP; they do not establish trusted-TLS, credential or
certificate rotation, Compose deployment or full-stack acceptance. A real
two-revision comparison and GitHub workflow execution cannot be demonstrated
without repository metadata/remotes. Existing Docker, dependency-scan and broader
service-coverage acceptance gaps remain open; no release checkbox was marked
complete.

## Durable shipment continuation

Shipment previously changed an order directly from CONFIRMED to SHIPPED without
committing its inventory holds. It now first commits a FULFILLING intent, history
entry and one task per reservation. This local transition fences cancellation
before any inventory mutation. The migration adds the new status to aggregate
and history constraints and creates the request/task tables.

The executor checks payment before retries, validates each hold's reservation ID,
order ID, SKU and quantity before mutation, and validates the acknowledgement
again. It leases one task at a time using SKIP LOCKED and a two-minute lease;
failures back off for one minute. A lost commit response is recovered by reading
the already committed hold. Acknowledgements require the current lease ID.
Permanent/expired/mismatched holds remain pending without producing SHIPPED.

After every task is acknowledged, payment is reread and checked again. A serialized
local finalizer commits SHIPPED, history, the existing shipment outbox event and
request completion together. A crash between task acknowledgement and finalization
can resume without consuming stock again. The executor rejects ambient database
transactions and preserves thread interruption. Its optional background worker
uses a bounded rotating JWT file and restores thread identity on exit.

The administrator ship action returns HTTP 202/FULFILLING while work remains,
with Location and Retry-After, or HTTP 200 after completion. Repeated completed
actions do not create another intent. GraphQL supports the new enum and decodes
the downstream 202. OpenAPI metadata advertises both outcomes and the public
status enum. Order error responses now explicitly use application/problem+json.
See [fulfillment operations and compatibility](order-fulfillment.md).

Adjacent test repairs make the existing order fixtures usable by real clients:
inventory/payment fake IDs are now UUIDs, and fake inventory commits are
idempotent with terminal-state guards. A real in-process fixture contract test
checks these rules. The old order OpenAPI suite had a class/file-name mismatch,
copied product requests and references to nonexistent JSON schemas. It now tests
actual order fields, problems and generated shipment documentation. The standalone
PostgreSQL persistence suite also initializes its required citext extension.

The focused clean order/GraphQL run passed tests and SpotBugs
(`fulfillment-focused-verification.log`); the subsequent fixture follow-up also
passed (`fulfillment-order-final-verification.log`). The final reactor run covers
the final API metadata, media type and schema checks. All 25 Python tooling tests
pass (`fulfillment-tooling-verification.log`).

`OrderPersistenceIT` now includes PostgreSQL tests for intent/task rollback,
lease expiry/stale acknowledgement, partial multi-line progress, single finalization,
completion/outbox rollback and transaction-free RPC execution. These tests are
compiled but have not run here because Docker is unavailable. No SQL locking,
restart, deployed authentication/mTLS, broker or physical-dispatch acceptance is
claimed. Worker identity provisioning, permanent-failure compensation, refunds
and reservation-expiry policy remain open; the broader lifecycle checkbox remains
unchecked.

The final clean shipment reactor run succeeded for all 17 modules, with 445 unit
and in-process tests, zero failures/errors/skips, and passing formatting/SpotBugs
(`fulfillment-reactor-final-verification.log`). Local pre/post coverage comparison
passed for all 13 production modules, with order instruction coverage increasing
from 42.008461% to 52.439595% (`fulfillment-coverage-verification.log`). This is a
local snapshot comparison, not a Git PR/base run. All ten executable jars and
actual layer extraction passed (`fulfillment-packaging-verification.log`); V10,
fulfillment classes and the GraphQL status are present in packaged artifacts
(`fulfillment-artifact-verification.log`). These results do not satisfy the
separate absolute combined unit/integration coverage thresholds or container
acceptance.

## Provider-backed refund and cancellation continuation

The old gRPC refund handler recorded immediate local completion without calling
a provider. It now shares the REST refund executor. New intents and their provider
keys commit before network calls; admission uses the existing full-key advisory
lock and a payment row lock. A pending/uncertain refund blocks another key from
spending the same balance. Request replay validates payment, exact amount/currency
and reason, and an omitted gRPC amount binds the remaining balance only once.

V5 adds provider keys and paired attempt leases. Dispatch uses a two-minute lease,
contacts the provider outside transactions and fences late replies. Stripe now
receives an actual Idempotency-Key header and local refund metadata. Known pending
provider references wait for webhook completion; uncertain POST replay stops after
20 hours, before Stripe's documented minimum key retention boundary. Legacy
pending intents without a provider key require ledger reconciliation.

Correlated refund webhooks validate provider/payment/refund ownership, provider
reference, amount and currency. A webhook arriving before the HTTP reply can
complete or fail the intent. Receipt, state changes and canonical full/partial
refund outbox events commit together; terminal duplicates and late replies cannot
apply money again. Aggregate charge-refund echoes are ignored to avoid counting
the same refund twice. Out-of-band refunds require an audit. Newly created gRPC
payment intents now record the configured provider.

The shared refund service enforces owner/administrator/recovery-scope method
authorization for both adapters. The in-process security test exposed wrapped
missing-authentication exceptions being mapped to INVALID_ARGUMENT; the gRPC
mapper now preserves UNAUTHENTICATED through that wrapper. REST maps the shared
payment-not-found exception to its existing 404 shape.

Order cleanup validates payment and order IDs before mutation. It cancels pending
payments and requests remaining refunds for confirmed/partially refunded payments
using its durable task ID as the refund key. Pending, failed, malformed or
mismatched outcomes stay queued. A success acknowledgement requires a fresh
terminal payment read. Confirmation/cancellation races retry against the new
payment state. Executors reject ambient transactions and cleanup preserves
interruption before work.

See [refund contracts, rollout and limits](payment-refunds.md). The existing
PostgreSQL suite now covers refund intent visibility before provider I/O,
competing-key admission, completion/outbox rollback and early webhook receipt.
It is compiled but not executed here because Docker is unavailable. Missing
webhook/provider GET reconciliation, standalone scanning, operator resolution,
legacy audit and deployed failure-injection acceptance remain open; the broader
backlog checkboxes remain unchecked.

Final local evidence for this continuation:

- Clean order compilation, 112 unit/in-process tests and SpotBugs passed in
  `refund-final-verification.log`. That reactor attempt then stopped on a payment
  constructor-cleanup compilation error, which was corrected before the final
  payment run.
- After cleaning payment-service, `refund-payment-final-verification.log` passed
  its complete five-module reactor, 92 payment tests, shared dependency tests,
  formatting, packaging and zero-warning/error SpotBugs. The earlier anonymous
  gRPC status failure is fixed and covered by the passing authorization suite.
- Current module reports contain 507 unit/in-process tests, zero failures,
  errors or skips. This combines the earlier full reactor and subsequent clean
  changed-module runs; it is not a claim of a new single 17-module run or Failsafe
  execution.
- Local coverage comparison passed all 13 production modules
  (`refund-coverage-verification.log`). Order instruction coverage increased from
  52.439595% to 53.670350%; payment increased from 36.792018% to 49.601140%.
  These local snapshots do not replace the actual Git PR/base gate or the
  unchanged 80% combined coverage policy.
- All ten executable packages and real layer extraction passed again in
  `refund-packaging-verification.log`. V5 matches the source bytes inside the
  payment executable jar; refund store/executor/handler classes and the order
  refund client are present. The four new PostgreSQL refund methods are in the
  compiled integration-test class but remain unexecuted without Docker.

## Refund recovery worker and operator continuation

Pending refunds with a known provider reference can now reconcile through a
read-only provider lookup when webhook delivery is missing. Stripe lookup uses
the existing refund GET endpoint, validates the reference before building the
path, and checks refund/payment IDs, exact amount and currency. HTTP errors,
missing refunds, unknown statuses and mismatches remain unresolved; lookup never
falls back to creating a refund. The development provider only reads its
in-memory ledger. Uncertain requests without a reference may reuse their durable
POST key inside the existing replay window.

V6 adds persisted attempts, next-attempt time, redacted error/attention codes and
due/attention indexes. Existing pending intents without provider keys are flagged
for audit. The opt-in worker scans at most ten due intents, skips busy payment
rows with SKIP LOCKED, and uses the existing two-minute lease. Short transactions
retain payment-first lock ordering; provider I/O happens after the claim commits.
Backoff increases from 60 seconds to a 15-minute maximum. Stale responses cannot
acknowledge another lease or duplicate the financial event.

Legacy intents, expired uncertain POSTs, provider mismatches and incompatible
payment states require attention rather than blind retries. A verified pending
webhook can supply a missing reference and clear an expired intent for GET
recovery; it preserves any live attempt lease. Terminal webhooks and lookup
completion continue sharing the payment/refund/outbox transaction.

Owners and administrators can inspect one refund and its recovery state; only
administrators can request its retry. The recovery scope alone cannot use this
operator action. Retry cannot alter the refund, bypass a live lease or override
provider/replay checks. Missing refund IDs and mismatched payment/refund paths
return 404. The response omits idempotency/provider keys and explicitly renders
the next-attempt timestamp as an ISO string. Tests use the production HTTP filter
chain and actual method-security policy with controlled JWT decoding.

The worker has its own single thread, conditional startup, bounded interval,
failure isolation and interruptible shutdown, so it does not occupy the outbox
scheduler. Gauges expose pending/attention counts; bounded counters distinguish
dispatch, lookup, provider outcomes and failures. See [refund recovery settings
and operations](payment-refunds.md).

The initial verification caught two deterministic test failures: replacement of
a throwing Mockito stub invoked the old stub, and standalone MVC serialized an
unformatted Instant numerically. The stub replacement uses doAnswer and the
public timestamp now declares string serialization. The final clean run verifies
these changes. Three additional PostgreSQL tests cover transaction-free lookup
with committed metadata, skipping a payment locked by another transaction, and
late pending-webhook recovery with expired/stale leases. They compile but require
Docker to execute. Unknown-reference ledger discovery/operator binding,
deployment, alert exercises and final refund/cancellation acceptance remain open.

Executed local verification for this continuation:

- `refund-recovery-final-verification.log`: clean five-module payment/shared
  reactor passed, with 144 payment unit/in-process tests, zero failures/errors/
  skips, passing formatting and zero-warning/error SpotBugs. The independent
  final-source formatting check also passed
  (`refund-recovery-format-verification.log`).
- Current module reports contain 559 passing unit/in-process tests. This combines
  earlier unchanged-module runs with the clean changed-module run; it does not
  claim a new full 17-module invocation or container/Failsafe execution.
- All 13 production modules passed the local before/after coverage comparison
  (`refund-recovery-coverage-verification.log`). Payment instruction coverage rose
  from 49.601140% to 56.702875%; other modules were unchanged. This local comparison
  is separate from Git PR/base execution and the unchanged combined 80% policy.
- `refund-recovery-artifact-verification.log`: packaged V6 exactly matches source
  bytes, and recovery service/worker/controller classes are in the executable jar.
  The three added PostgreSQL recovery tests are in the compiled test class;
  Docker execution remains unavailable.
- `refund-recovery-packaging-verification.log`: all ten plain/executable service
  jars and actual layer extraction passed. Updated documentation links resolve.

## Audited operator refund reconciliation continuation

Unknown-reference pending refunds now have an administrator-only reconciliation
action. The operator supplies a candidate found in the provider ledger and a
bounded reason. The actor subject/issuer come from the authenticated JWT. A
committed audit intent and existing refund lease precede provider I/O. The action
uses only a strict GET lookup; it never creates a provider refund or changes the
local refund ID, amount or provider key.

Stripe must return the candidate ID, matching PaymentIntent/amount/currency and
the original local refund/payment IDs in metadata. Matching amount alone cannot
identify a partial refund. Unsupported implementations fail closed rather than
falling back to the ordinary lookup or POST. Invalid/missing metadata, HTTP
failure and unrecognized outcomes remain unresolved without binding a reference.

V7 adds a reconciliation audit table with immutable request fields in the JPA
mapping and transactionally updated outcomes. Its unique payment/provider-refund
index prevents duplicate reference binding; existing duplicates cause migration
failure and require reviewed ledger repair rather than automatic deletion.
The finalizer rechecks lease, balance and reference uniqueness, then commits
financial/refund state, the success outbox event and audit outcome together.
Verified pending results enable normal GET recovery. A webhook or newer attempt
can supersede a late operator reply without applying money again.

The POST and bounded recent-history GET use
`/api/v1/payments/{paymentId}/refunds/{refundId}/reconciliation`. Both require an
administrator; payment ownership and the recovery scope alone are insufficient.
Busy, blocked, unresolved, pending, terminal and superseded outcomes are explicit.
The response omits provider/idempotency keys. Reasons reject NUL before database
access, so malformed text cannot cause a persistence failure after authorization.
Audit history is limited to twenty records, and a bounded outcome counter exposes
operator results. See [operator workflow, preflight and limits](refund-reconciliation.md).

The first clean payment/shared run passed 191 payment tests and zero-warning/error
SpotBugs (`refund-reconciliation-verification.log`). Additional null/NUL validation
cases are included in the final clean run. New PostgreSQL tests cover committed
audit visibility before GET, verified pending-to-completed outcomes, atomic
audit/refund/outbox rollback and database-level duplicate-reference rejection.
Initial execution exposed Docker client API negotiation and currency mapping
failures; the follow-up below records the fixes and database results. No deployed
ledger access, complete concurrency, issuer/mTLS or audit-retention acceptance is claimed.
Automatic provider-ledger discovery, legacy repair and the wider lifecycle tasks
remain open.

Final local verification:

- `refund-reconciliation-final-verification.log`: clean five-module payment/shared
  reactor passed with 194 payment tests, zero failures/errors/skips, passing
  formatting, packaging and zero-warning/error SpotBugs. Fifty payment tests were
  added compared with the preceding continuation.
- Current module reports contain 609 passing unit/in-process tests. This combines
  the previous unchanged-module runs with the current clean changed-module run;
  it is not a new single full-reactor or Failsafe/container run.
- All thirteen production modules passed the local coverage comparison
  (`refund-reconciliation-coverage-verification.log`). Payment instruction coverage
  rose from 56.702875% to 61.594203%; other modules were unchanged. The Git PR/base
  run and unchanged combined 80% coverage policy remain separate acceptance gates.
- `refund-reconciliation-artifact-verification.log`: V7 source bytes and the audit
  entity/repository, reconciliation store/service and controller match the
  executable artifact. The three new PostgreSQL methods are in the compiled
  integration-test class; subsequent database execution is recorded below.

Docker/build follow-up:

- The engine is Docker Desktop 29.9.2, reachable through the Windows named pipe.
  The old BOM order silently selected Spring Boot's Testcontainers 1.19.8 despite
  the project's 1.20.3 property. Moving the Testcontainers BOM before Spring
  Boot's and selecting 1.21.4 makes the Docker-compatible pin effective. Fresh
  test classpaths confirm 1.21.4 across the reactor, without an API override.
- The first real database attempt applied all seven payment migrations and
  exposed a pre-existing currency mapping mismatch. Payment/refund overrides now
  name PostgreSQL's `bpchar(3)` type, matching the existing CHAR(3) columns without
  altering historical migrations or financial records.
- Repository startup exposed convenience getters being mistaken for persistent
  `paymentId`/`refundId` attributes. Refund queries now explicitly traverse
  `payment.id`; bounded audit history explicitly traverses `refund.id`. These
  are exercised against PostgreSQL rather than only mocked repositories.
- The initial thirteen-case database run passed twelve cases, including all
  three new reconciliation scenarios. The cross-protocol creation race fixture
  configured gRPC as `noop` while REST requested `stripe`, so whichever request
  won determined whether the provider guard rejected the retry. The fixture now
  configures both paths as Stripe and separately asserts rejection of a genuine
  provider mismatch; the production guard is preserved.
- `refund-reconciliation-reactor-final-verification.log`: a fresh clean
  seventeen-module unit/package run passed in 9m50s. All 609 unit/in-process tests
  passed with zero failures, errors or skips, including 194 payment tests.
  This supersedes the earlier combined-report evidence. Full `verify`, the
  absolute combined coverage gates and e2e acceptance remain separate.
- `coverage/after-refund-reconciliation-docker29.json` records the fresh unit-only
  measurement before integration execution. All thirteen modules passed the
  local comparison against `after-refund-recovery.json`
  (`refund-reconciliation-coverage-final-verification.log`); payment instruction
  coverage is 61.594203%, up from 56.702875%.
- `refund-reconciliation-packaging-final-verification.log`: all ten executable
  packages passed plain-jar, Boot launcher and actual layer extraction checks.
  V7 bytes and all reconciliation components are present in the rebuilt payment
  executable. This does not establish image builds or deployed service health.

Final verification after the repository and fixture fixes:

- `refund-reconciliation-complete-verification.log`: a clean five-module
  payment/shared run passed unit tests, packaging, JaCoCo reporting, SpotBugs and
  all thirteen `PaymentCreationConcurrencyIT` PostgreSQL cases, with no test
  failures, errors or skips. This includes all three new reconciliation cases.
  Flyway applied V1â€“V7 and Hibernate validated the real schema. Provider intent
  visibility, leases, recovery, webhook deduplication, audit rollback, reference
  uniqueness and the cross-protocol creation race were exercised against the
  database. Other container suites and deployed delivery are not covered by this
  focused command.
- The final payment unit count remains 194; current reactor unit reports total
  609, combining the clean seventeen-module run with the subsequent clean
  payment/shared rerun. JaCoCo XML was generated immediately after the unit
  package phase, before Failsafe execution, so the refreshed coverage snapshot
  remains unit-only. All thirteen coverage comparisons passed again.
- `refund-reconciliation-source-artifact-final-verification.log`: zero SpotBugs
  findings/errors in shared domain, proto, events and payment; packaged V7 bytes,
  explicit relationship queries, bounded audit traversal and PostgreSQL currency
  mappings match the final source. The executable packages were checked again
  after the final payment rebuild.

Full reactor `verify`, e2e/Compose journeys, combined coverage gates, production
JWT/mTLS, external provider delivery, legacy ledger repair and audit retention
exercises remain open. The broader backlog items remain unchecked.

## Product/customer PostgreSQL and HTTP continuation

The next foundation work repairs deterministic product/customer integration
failures rather than treating container startup errors as skipped acceptance.
See [scope and commands](catalog-customer-verification.md).

- Removed duplicate auditing registration and corrected customer Hikari YAML.
  Spring owns each test database's lifetime, matching the context and pool;
  application smoke tests inherit random-port configuration.
- Real Hibernate validation exposed product money column names, `citext`
  SKU/slug/email mappings and fixed-width country/currency types. Mappings now
  match the existing V1 schema, without rewriting migrations.
- Moved CORS/pagination properties from `spring.app` to root `app`, enabled CORS
  in both security chains, and repaired shared CORS registration. An unrelated
  MVC configurer no longer suppresses the shared policy; explicit named service
  overrides remain supported.
- Malformed JSON maps to 400 and method-security denials to 403. Customer address
  reads require an administrator or the matching verified customer claim, as
  profile reads already do. Foreign and claimless address access are tested.
- Replaced a placeholder unlinked-user test with an actual 404 request, isolated
  mutable product CRUD fixtures, and removed an invalid SKU-prefix assumption
  from category filtering. Test JWT decoders distinguish invalid credentials.
- Added real HTTP boundary tests and complementary production-security MVC tests
  for batch semantics/bounds, sorting, credentials, CORS and ownership. Customer
  sorting now allows actual `firstName`/`lastName` attributes and rejects the
  nonexistent `name` attribute before repository access.

Initial verification:

- `catalog-customer-schema-verification.log`: both application smoke tests passed
  against PostgreSQL 16.4 after Flyway V1â€“V2 and Hibernate validation.
- `catalog-customer-cors-verification.log`: all five selected reactor modules
  passed; 43 product and 31 customer integration tests passed with zero failures,
  errors or skips. Product/customer unit counts were 22/19 at this stage. Final
  customer sorting regressions are included in the subsequent clean run.
- The standalone product coverage diagnostic generated a combined report but its
  unqualified `jacoco:check` invocation lacked execution-specific rules. It is
  not coverage-gate evidence; lifecycle verification is required below.

Final clean unit/static/package evidence:

- `catalog-customer-clean-quality.log`: clean five-module reactor passed all 112
  unit/in-process tests (domain 37, common web 33, product 22, customer 20), with
  zero failures, errors or skips. Formatting, executable packaging and SpotBugs
  passed; all four analyzed modules have zero findings/errors.
- `coverage/after-catalog-customer.json` was exported after that unit-only build,
  before any integration execution. It records 625 current reactor unit tests,
  combining the changed modules with the previous clean unchanged-module run;
  this is not a fresh seventeen-module test run.
- `catalog-customer-coverage-verification.log`: all thirteen local per-module
  comparisons pass against `after-refund-reconciliation-docker29.json`. Product
  unit instruction coverage increased from 30.193616% to 42.020725%; customer
  increased from 18.339852% to 29.741379%; common web remains 100%. Absolute
  service coverage and Git PR/base comparison are separate gates.
- `catalog-customer-packaging-verification.log`: all ten executable service
  packages pass plain-jar, Boot-launcher and actual layer-extraction checks after
  the product/customer rebuild. Unchanged packages come from the preceding
  reactor build; this does not establish image builds or runtime health.

Final lifecycle verification:

- `catalog-customer-final-verify.log`: the five-module `verify` run executed all
  112 unit/in-process tests and all 76 product/customer integration tests
  (product 43, customer 33), with zero failures, errors or skips. A source-to-report
  audit confirms every selected unit/integration suite executed, including the
  final customer sorting cases.
- Shared domain/web coverage gates passed. Product/customer lifecycle verification
  failed on the existing 80% instruction thresholds: JaCoCo reports product 0.68
  and customer 0.49. No threshold or exclusion was relaxed. Passing tests and
  unit-coverage regression checks do not satisfy these absolute coverage gates.
- Remaining coverage work includes customer profile/address update and removal,
  identity linking and preference lifecycle behavior, plus product image/status
  and filtering boundaries. These paths need meaningful regression coverage and
  fixes for any failures it exposes before claiming the service gates are green.

Full reactor `verify`, all other container suites, deployed/e2e journeys and the
broader security/compatibility review remain open. The corresponding `TASKS.md`
items remain unchecked.

## Product/customer lifecycle and coverage continuation

The next continuation exercises the behavior missing from the preceding coverage
reports. The new tests use committed PostgreSQL transactions, not rollback-only
fixtures, and keep the existing service coverage policies unchanged.

`catalog-lifecycle-initial.log` reproduced five defects: discontinued products
appeared in default listings; first-time preference writes ignored all three
boolean choices; newly added addresses returned null IDs; adding and updating
defaults could each violate the immediate partial unique index.

Repairs serialize owned address/preference writes on the customer row, flush
default demotions before promotions in the same transaction, and flush newly
added addresses before mapping their response. Default changes remain inside the
aggregate and reject foreign addresses; explicit false clears a flag and an
omitted update flag preserves it. Preference creation applies every choice.
Product listings default to active status, and replacement images receive IDs
before the updated DTO is returned.

Additional regressions exercise concurrent defaults, rollback after a failed
promotion, movement between address types, HTTP profile/address mutations,
identity-link conflicts, consent replacement, category hierarchy, image orphan
removal, database ownership and SKU identity/format boundaries. See
[the lifecycle contract](catalog-customer-verification.md#lifecycle-behavior).

Focused repair verification:

- `catalog-lifecycle-repair-verification.log`: all 31 product unit/in-process and
  six new product lifecycle tests pass. Its customer build exposed an access
  modifier error in the initial repair, corrected by retaining aggregate-owned
  default changes.
- `customer-lifecycle-repair-verification.log`: all 24 customer unit/in-process
  and sixteen new customer lifecycle tests pass, including concurrent default
  writes and rollback after a failed insert.
- `catalog-lifecycle-coverage-diagnostic.log`: both configured service 80%
  coverage checks pass against the focused execution data. The clean complete
  suite run below establishes the final lifecycle result.

Clean unit/static/package verification:

- `catalog-lifecycle-clean-quality.log`: the five-module clean build passes 125
  unit/in-process tests (domain 37, common web 33, product 31, customer 24), with
  zero failures, errors or skips. Formatting, packaging and SpotBugs pass with
  zero findings/errors in all four analyzed modules.
- `coverage/after-catalog-lifecycle.json` preserves the unit-only measurement
  before integration execution. It records 638 current reactor unit tests,
  combining the changed modules with earlier unchanged-module reports. It is
  not a new seventeen-module execution.
- `catalog-lifecycle-unit-coverage-verification.log`: all thirteen local module
  comparisons pass against `after-catalog-customer.json`. Product unit coverage
  increases from 42.020725% to 43.371017%; customer increases from 29.741379% to
  42.315789%. These comparisons use unit-only reports and are separate from the
  combined lifecycle 80% service policies.
- `catalog-lifecycle-packaging-verification.log`: all ten executable packages
  pass plain-jar, launcher and layer-extraction checks after the product/customer
  rebuild. Unchanged packages retain their preceding reactor-build provenance;
  image builds and deployed health are not established.

Final complete focused lifecycle result:

- `catalog-lifecycle-final-verify.log`: all five selected modules pass `verify`.
  All 125 unit/in-process and 98 PostgreSQL integration tests pass with zero
  failures, errors or skips. Product/customer integration counts are 49 each,
  including the six/sixteen new lifecycle cases alongside every existing suite.
- Both unchanged 80% service instruction-coverage gates pass: product 91.64%,
  customer 91.51%, using their existing class-file exclusions. Shared domain/web
  gates pass as well. This supersedes the earlier product 0.68/customer 0.49
  failure; no threshold or exclusion was relaxed.
- `catalog-lifecycle-final-evidence.log` confirms every selected source suite has
  passing reports, including the SKU parameterized cases and aggregate ownership
  tests. Packaged changed classes, application configuration and V1â€“V2 migrations
  match the build/source. `catalog-lifecycle-policy-evidence.log` records the
  configured exclusions, instruction counters and successful Maven gate output.

The product/customer milestone is complete. Full seventeen-module `verify`, all
remaining container suites, Git PR/base execution and deployed/e2e acceptance
remain open; broader backlog checkboxes remain unchecked.

## Order PostgreSQL and ownership continuation

The next baseline dependency is order-service. The initial six-module run
(`order-reactor-integration-initial.log`) passes 112 order unit tests and five
checkout-journal database cases, but HTTP startup fails on a Feign/gRPC bean-name
collision and JPA persistence startup fails on the order-line currency mapping.

Repairs give the interceptors distinct bean names, enable auditing once, and map
existing `citext`, fixed-width country/currency and `numeric(19,4)` columns without
editing historical migrations. Spring owns each HTTP class's database/RPC server
lifetimes. RPC fakes move to test sources; HTTP stubs serialize quoted names
correctly, and confirmation tests use newly created orders with verified payment
evidence rather than an incompatible seed order.

Public order UUID/number reads, placement and cancellation now require an
administrator or verified customer ownership; creation checks the requested
customer against the JWT claim. Missing, malformed and non-string claims fail
closed, without falling back to the subject. Regression tests cover foreign and
claimless access. The complete policy and claim requirement are documented in
[order verification](order-verification.md).

The first repaired run passes all thirteen PostgreSQL persistence/journal cases
and the existing API/repository suites. Boundary failures expose optional JSON
actions being sent as form media and mapped to generic 500 errors. Fixtures now
send JSON for those actions; unsupported media use a shared 415 problem helper
and a real HTTP regression. Browser origins, root pagination binding and sorting
the actual `total.amount` property are covered. Kafka topic provisioning/outbox
draining are disabled only in the database-focused HTTP fixture.

Clean unit/static/package evidence:

- `order-integration-clean-quality.log`: six selected modules pass the clean
  build, with 261 unit/in-process tests (order 123; shared domain/web/proto/events
  37/33/5/63), zero failures/errors/skips, and zero SpotBugs findings/errors in all
  five analyzed modules. Formatting and executable packaging pass.
- `coverage/after-order-integration.json` records 649 current unit reports,
  combining changed-module execution with previous unchanged-module reports.
  Refreshed clean unit counters cover order and shared domain/web/events; the
  other nine counters are retained from the saved unit-only
  `after-catalog-lifecycle.json`. Current product/customer target XML contains
  integration coverage and is deliberately not used for those counters.
  `coverage/after-order-integration.provenance.json` and
  `order-integration-coverage-provenance.log` record this provenance. This is not
  a new full seventeen-module unit execution.
- `order-integration-unit-coverage-verification.log`: all thirteen local unit
  comparisons pass. Order increases from 53.670350% to 54.696044%; common web
  remains 100%. Other counters are unchanged. This is separate from the absolute
  combined service coverage gate and Git PR/base execution.
- `order-integration-packaging-verification.log`: all ten executable packages
  pass plain-jar, launcher and actual layer-extraction checks. Both order jars
  exclude the moved fixture RPC implementations; unchanged executable packages
  retain their preceding build provenance. Deployment/image acceptance remains
  separate.

Final order verification:

- `order-integration-final-verify.log`: all six selected modules succeed; 261
  unit/in-process tests and 42 integration tests pass with no failures, errors or
  skips. This includes eight persistence, five journal, three OpenAPI, five
  repository, eleven boundary and ten controller cases.
- The unchanged configured order instruction gate passes at 80.1232%
  (3,511 covered / 4,382 counted instructions after the existing exclusions).
  This combined unit/integration metric is separate from the saved unit-only
  regression snapshot.
- A thread inspection during the long run showed an actively authenticating
  PostgreSQL connection in a persistence test, not a deadlock. The run completed
  normally; no process was terminated and no timeout/gate was weakened.

`order-integration-evidence-audit.log` checks all 25 source/report suites (165
order tests), with no missing suites, failures, errors or skips, and confirms
that neither order artifact contains fixture RPC implementations.

The complete seventeen-module reactor verification is the next baseline check.
Full-stack/e2e, deployed identity/transport, and broker delivery acceptance remain
open.

## Full reactor and integration-fixture continuation

`full-reactor-continuation-verify.log` records the complete fail-at-end reactor
run: twelve modules succeed, four fail, and platform-tests is skipped because
its dependencies fail. This was a diagnostic `verify`, not a clean release run.
Product/customer/order rerun all 49/49/42 integration cases successfully; both
banking modules, the provider simulator and shared modules also succeed.

The remaining failures are classified as follows:

- Gateway: all nine HTTP endpoint cases attempt the default REST Assured port
  instead of the application's random port. The fixture now binds that port and
  models rejected credentials as `BadJwtException` without echoing tokens.
- Inventory: nine unit tests pass, but its unchanged 80% instruction gate fails
  at 23%. Business behavior and database coverage remain required.
- Payment: 194 unit cases and thirteen database cases pass. The application-flow
  fork exhausts native memory before reporting its three cases; that suite has
  no passing evidence from this run. Test Kafka containers now use a bounded
  128?256 MiB heap to reduce their resource footprint.
- Notification: the application cannot start with two component classes named
  `WebhookTestPublisher`. The subscription controller now uses the delivery
  publisher and the duplicate DLQ publisher is removed. Embedded Kafka also
  fails binary linkage because only kafka-clients was pinned to 3.8.0 while
  broker artifacts use Boot's 3.7.1 management. Removing the isolated pin keeps
  the complete Kafka family under the existing Spring Boot BOM.

The first clean repair rerun (`reactor-repair-verify.log`) ends during the shared
event unit phase without a completion summary. It is not verification evidence.
The resumed clean run is recorded in `reactor-repair-resumed-verify.log`; results
will be recorded after it completes. Inventory's gate, platform suites and e2e
acceptance remain open.

## Inventory transactions and gateway acceptance

`inventory-bulk-postgres-verify.log` first verifies ten new bulk lifecycle cases
and five PostgreSQL cases, but the existing instruction gate remains at 54%.
Additional domain and generated-client RPC contracts then cover stock
conservation, terminal holds, unary read/reserve/commit/release, client streaming,
server snapshots/updates/cancellation and production exception translation.

`inventory-gateway-payment-verify.log` records clean successful inventory and
gateway verification, followed by a separate payment startup failure:

- Inventory: 34 unit/in-process and five committed PostgreSQL tests pass with no
  failures, errors or skips. The configured 80% instruction gate passes at
  90.5619% (1,660 covered / 1,833 instructions after its existing exclusions).
- Gateway: 59 unit/in-process and eleven HTTP endpoint tests pass with no
  failures, errors or skips. Two added administrator customer-create tests
  verify bearer relay and the duplicate-email conflict contract. The unchanged
  80% gate passes at 80.4945% (293 / 364 counted instructions).
- Payment: 194 unit and thirteen PostgreSQL cases pass. With Docker restored and
  bounded test Kafka heap, the three flow cases reach application startup and
  expose Hibernate looking for outbox_event in public. Flyway uses payments;
  Hibernate now explicitly uses the same schema. Its rerun is recorded in
  `payment-flow-schema-verify.log`; no passing flow result is asserted yet.

Bulk reservation previously invoked a local annotated helper, leaving stock
changes outside a transaction and omitting outbox events. It now delegates each
item through the existing Spring ReservationHandler proxy. The 100-item bound,
canonical single-order validation, partial commits and complete-stream recovery
contract are documented in [inventory reservations](inventory-reservations.md).
The PostgreSQL constraint-failure case proves that the failing item rolls back
its stock, hold and event, while earlier commits survive and replay safely.

The RPC contract tests use mocked repositories and an in-process transport;
database tests separately call real production Spring transaction proxies.
These results do not establish deployed mTLS/issuer enforcement, broker delivery
or full lifecycle/release acceptance. Notification/platform/full-reactor/e2e
verification remains open.

## Payment startup and contract completion

The explicit Hibernate schema fix gets PaymentFlowIT past JPA validation. The
next startup error is the banking retry policy's obsolete
com.omp.payment.client.BankingUnavailableException name; the configuration now
uses the existing com.example.omp class.

`payment-flow-retry-binding-verify.log` passes the three selected flow cases, but
the existing coverage gate fails at 74.11%. Its retained reports/coverage are
targeted evidence, not a clean complete verification. Added contracts then cover
all stream lifecycle statuses, order/customer filtering, readiness/cancellation,
disconnected observers, development-provider refund lookup/replay, webhook
verification/parsing, HTTP status/DTO mapping, raw body/header forwarding and
redacted retryable webhook processing errors.

`payment-contracts-final-verify.log` is the complete clean five-module run:
326 unit/in-process tests and all sixteen PostgreSQL/Kafka cases pass with zero
failures/errors/skips. Payment itself passes 221 unit and sixteen integration
cases. The unchanged 80% gate passes at 82.3936%. See
[payment verification scope](payment-verification.md) for the distinction between
standalone controller/observer doubles, real provider signing tests and committed
database/application-flow tests.

`inventory-gateway-payment-evidence-audit.log` matches all source suites with
passing reports for the three focused modules and checks the configured gates:
gateway 80.4945%, inventory 90.5619%, payment 82.3936%. The archive
`coverage/inventory-gateway-payment-integration-evidence.zip` retains their unit,
integration and JaCoCo XML plus source hashes before the reactor unit-only clean.
Its companion JSON records provenance; subsequent source changes invalidate the
associated evidence. This preserves integration results without mixing their
coverage counters into the unit-only regression snapshot.

The clean seventeen-module unit/package/static-analysis continuation completes
successfully in `reactor-unit-static-final-verify.log`:

- `reactor-unit-source-evidence-final.log`: 701 unit/in-process tests pass with
  no failures, errors, skips or missing source suites. All fourteen SpotBugs XML
  reports have zero bugs/errors. Both order jars exclude fixture RPC services;
  both notification jars exclude the removed duplicate publisher.
- `coverage/after-inventory-payment-contracts.json` is a fresh unit-only
  measurement of all thirteen production modules from this full clean run, with
  701 tests. No retained integration counters are used. Its provenance sidecar
  names the build and earlier local baseline.
- `reactor-unit-coverage-comparison-final.log`: all thirteen local comparisons
  pass against the earlier unit-only `after-order-integration.json`. Inventory
  rises from 21.493441% to 83.366935%; payment rises from 61.594203% to 70.969203%.
  Notification rises slightly after duplicate code removal; other ratios are
  unchanged. These whole-module unit measurements differ from the configured
  combined unit/integration gates quoted above. Git PR/base execution remains
  separate CI acceptance.
- `integration-source-provenance-final.log`: all 244 source files in the three
  focused integration manifests still match their archived passing runs.
- `reactor-packaging-final-verify.log`: all ten freshly rebuilt executable
  modules pass plain-jar, launcher and actual Docker layer-extraction checks.
  This verifies package structure, not image deployment.

Notification/platform container suites, complete reactor verify, e2e/deployed
release acceptance and the broader backlog remain open.

## Notification startup, contracts and replay

Notification startup now uses distinct bean names for the JDK HTTP transport and
the webhook sender. An explicit enum converter preserves the existing lowercase
PostgreSQL owner constraint without changing historical migrations. The full
application integration fixture owns PostgreSQL/Redis containers through Spring,
binds embedded Kafka explicitly, isolates context lifetimes and uses random
application/management ports. Topic replication remains three in production and
is explicitly one in single-broker tests.

Outbound listeners now commit records after successful processing. Delivery and
retry publishers wait at most ten seconds for broker acknowledgment, propagating
rejection, timeout and interruption. DLQ database failures propagate instead of
being swallowed. Keycloak's nested realm roles are read as a collection and
normalized for administrator authorization; invalid pagination returns 400.
Missing and already replayed DLQ entries return 404/409 while retaining the
existing IllegalArgumentException/IllegalStateException Java contracts.

The added contracts cover owner-isolated HTTP management, secret rotation,
disable/enable, customer denial of administrator operations, signed HTTP delivery,
committed Kafka offsets and persisted failure/history followed by administrator
replay with the same delivery ID. Unit regressions exercise transport/status
classification, retry identity/jitter, duplicate successful delivery, missing or
disabled subscriptions, exhausted attempts, audit-storage failures and failed
replay publication. See [notification verification](notification-verification.md)
for the real infrastructure versus mocked ownership/network boundaries.

Diagnostic evidence is classified explicitly:

- `notification-integration-diagnostic.log` exposes the transport/component bean
  collision. The repaired fixture then exposes the enum/database mismatch.
- `notification-contracts-verify.log` passes 78 notification unit/in-process and
  twelve integration cases, but correctly fails the existing 80% gate at
  76.0992%.
- `notification-contracts-final-verify.log` adds the failure/replay path and passes
  82 notification unit/in-process and thirteen integration cases at 85.6679%.
  A later Java exception compatibility adjustment requires fresh verification.
- `notification-acceptance-verify.log` identifies a timing flaw in the new DLQ
  assertion: the subscriber can receive a request before the DLQ row commits.
  The test now asserts row presence within Awaitility before unwrapping it, so
  the transaction's visibility is awaited. This failure is classified as a test
  synchronization defect, not hidden with sleeps, skips or changed coverage rules.

The service README now reflects implemented Kafka/Redis delivery, private
notifications, explicit unsubscribe cleanup and persistent outbound state.
Invalid-event quarantine, atomic database/broker handoff, concurrent replay,
payload enrichment, SSRF/DNS protection, deployed recovery and platform/full
reactor/e2e acceptance remain separate open work.

Final acceptance is recorded in `notification-acceptance-final-verify.log`:

```sh
./mvnw -pl notification-service -am -Pstatic-analysis clean spotless:apply verify
```

The clean four-module run passes 178 unit/in-process tests (33 shared web, 63
shared events, 82 notification) and all thirteen notification integration cases.
There are no failures, errors, skips or missing source suites. All three focused
SpotBugs reports have zero findings, analysis errors and missing classes. The
existing 80% instruction gate passes at 85.6679% (5,009 covered / 5,847 counted
instructions); its exclusions and threshold are unchanged.

`coverage/notification-integration-evidence.zip` retains the passing test,
JaCoCo/static-analysis XML and final build log. Its companion JSON records source
and build-input SHA-256 hashes, test counts, the configured gate and archive hash.
Subsequent source changes invalidate the associated evidence. This is focused
combined unit/integration coverage; the earlier reactor unit-only snapshots are
not refreshed from these mixed counters. No new full-reactor or e2e success is
asserted.

## Platform contracts and packaged journey continuation

Platform fixtures now load each service's own configuration and migrations on
the shared classpath, retain JDBC URL parameters when selecting schemas, supply
Redis, explicitly import the fast-retry scheduler and close application contexts
before removing infrastructure. Notification disables the gRPC server that
arrives through the payment test dependency. Tests wait for committed source,
delivery/retry and DLQ offsets before clearing state; subscriber executors close.
The payment PostgreSQL fixtures also use Testcontainers URL parameters directly.

Timeout tests now send real delayed HTTP responses instead of passing without
assertions. Signature tampering changes actual delivered bytes; permanent URL
failure is tested without external DNS while respecting the database scheme
constraint. The disabled-subscription worker test queues a delivery directly,
representing a circuit that opens after matching. The payment journey uses
independent application contexts and HTTP ports, a local RSA/JWKS issuer and
production JWT validation, authenticated subscription creation, real Kafka and
committed payment/outbox/delivery state.

These contracts exposed two production defects. The webhook request client
ignored its configured timeout; it now uses `webhook.delivery.timeout-seconds`
with startup validation of the one-to-sixty-second range. Refunds emit canonical
`PAYMENT_REFUNDED`, while existing subscriptions use `payment.refunded`.
Matching now bridges the nine published canonical/dotted pairs, preserves the
subscribed spelling and deduplicates subscriptions listing both. Delivery IDs
retain their existing event/subscription identity. The synthetic test endpoint
also returns `status: queued` alongside its delivery ID.

`platform-journeys-verification.log` records a clean focused eight-module package
with 457 passing unit/in-process tests (98 notification), followed by all 42
cases in the twelve default platform suites without failures/errors/skips.
The separately tagged packaged container journey failed to receive its callback;
that failure means the overall command is not build acceptance. The default
platform milestone is checked independently; packaged/full-reactor/full-stack
acceptance remains open pending the subsequent runs.

The packaged fixture now copies fresh executable jars into separate Java 21
containers with its own PostgreSQL/Kafka/Redis, allocated ports, signed JWTs and
subscriber signature/history assertions. The obsolete Compose fixture and
authentication-bypass filter are removed. A required
`packaged-payment-journey` CI job builds/runs that focused suite and requires its
nonempty passing report; GitHub execution is not claimed. See
[platform verification](platform-verification.md) for commands, boundaries and
the final execution evidence as it becomes available.

The packaged diagnostic localized a startup-order defect: payment's outbox rows
were published, but the webhook consumer committed no records and no attempts
were stored. Notification disables domain-topic provisioning by default, while
its consumers previously allowed Kafka to create topics implicitly. A consumer
starting first could create a one-partition topic before payment provisioned
three; the existing assignment then missed newly added partitions until its
metadata refresh. Both standard notification consumers and custom webhook
factories now disable consumer-driven topic creation. The packaged fixture
starts notification first and requires every broker partition to be assigned,
including all three partitions of each domain topic. This prevents a randomly
chosen payment partition from hiding an incomplete listener assignment.
Kafka's own `Utils.murmur2` partition calculation maps the two later failed
packaged payments to partition 1; the successful payment in the full-reactor
diagnostic maps to partition 0. This agrees with the published outbox records,
empty consumer offsets and absent delivery history in the failed run.

`packaged-topic-creation-verify.log` then made the defect deterministic during
startup: Kafka had three partitions per domain topic, while the webhook group
was assigned only partition zero. The remaining WebSocket factory was also
creating topics because it constructs its own consumer properties rather than
using the YAML consumer properties. Disabling implicit creation in both custom
configurations fixes that path.

`packaged-all-consumers-verify.log` passes the rebuilt eight-module command with
457 unit/in-process tests and both packaged cases, without failures, errors or
skips. Complete assignments, authenticated payment, subscriber HMAC, persisted
successful history, idempotent payment replay and malformed/tampered token
rejection pass. The packaged milestone is checked in `TASKS.md`; its passing
report/log, source/build-input hashes and executable jar hashes are retained in
`coverage/packaged-payment-evidence.zip` and its companion JSON. A fresh full
reactor is running separately; neither that acceptance nor the external
full-stack suites is inferred from this focused result.


## 2026-11-05 contract and external-suite continuation

The required contract job now checks protobuf descriptors against both the
published v1 fixture and the exact PR base. It also freezes the effective SOAP
v1 WSDL/XSD, checks bank/adapter copies and embedded schemas, then regenerates
and verifies JAXB clients. The bank serves the checked-in WSDL, preserving SOAP
actions and fault declarations. The published WSDL embeds its XSD so clients do
not follow an unmapped relative import URL. Contract ownership/versioning and
local commands are documented in contract-evolution.md. The Maven wrapper
distribution now has a verified SHA-256 checksum.

All 32 Python tooling tests pass in 	ooling-contract-verification.log, including
SOAP type/operation/action drift, baseline replacement, imported/embedded schema
equivalence and missing/malformed files. The clean reactor has also passed all
seven protobuf tests. The focused banking verification subsequently passed all
25 tests with unchanged coverage gates (`banking-contract-verification.log`).
Complete reactor acceptance remains pending. GitHub execution and branch protection are not inferred
from local configuration.

The external suites previously contained an unfinished lifecycle case, an
UnsupportedOperationException cancellation helper and a shared mutable seed
order. They now create independent orders and read committed payment/reservation
references from additive REST fields. They exercise payment-backed shipment,
invalid transitions, cancellation/retry, inventory release/commit and refunds.
The WebSocket case uses JSON object conversion, bounded subscription-metric
readiness and unsubscribe checks. Test clients use mTLS by default, per-call
deadlines, refreshing development credentials and resettable channel cleanup.
The development issuer now supplies Alice's verified customer_id mapping.

docker-compose.e2e.yml provides loopback gRPC/management access, deterministic
noop refunds, single-broker webhook topics, explicit producer broker endpoints
and fresh-database service-role passwords. Base initialization creates roles
without passwords; the additional e2e SQL supplies environment-backed passwords
with SQL literal quoting and statement logging disabled. Existing volumes are
not reset or silently migrated. See external-e2e.md for requirements and limits.

The initial build attempts were blocked by the host Java trust store and missing
dependencies. A temporary loopback bridge fetches Maven Central through Python's
working HTTPS trust store without disabling TLS checks; downloads remain in the
ignored workspace scratch directory. Two large JAR downloads were recovered
with independently fetched SHA-1 checksums over verified HTTPS. Read-only
OneDrive build-output attributes were cleared inside verified workspace targets.
Docker engine 29.9.2 is available; a temporary official CLI and checksum-verified
Compose client validate the merged e2e configuration. These environment repairs
are not changes to release dependencies or claims of deployment acceptance.

The fresh wrapper command is -Pstatic-analysis clean spotless:apply verify,
with temporary settings/local-repository overrides for this environment.
aseline-clean-reactor-verify.log is the current run. Existing coverage gates
and suite requirements are preserved. Full-reactor, external e2e, supply-chain,
load, resilience and operational acceptance remain open until their own passing
evidence is recorded. Older source-bound archives describe their recorded
revisions; changed order, banking and build inputs require fresh evidence.

## 2026-11-06 deployed transport and topology continuation

Deployed checkout exposed startup defects that unit-only packaging could not
establish: order's required `grpc-inprocess` library was test-scoped; inventory
had no servlet resource-server auto-configuration/JWT decoder or functioning
management listener. Order now has the runtime dependency. Inventory has a
management-only servlet setup with signed-JWT metrics security. Its clean focused
verification passes 41 tests and the unchanged 80% coverage gate at 89.93%; the
source-bound results are in `coverage/inventory-runtime-evidence.zip` and JSON.

Order now waits for healthy inventory/payment, resolves their DNS names rather
than caching a failed static resolution, negotiates TLS, and explicitly enables
client-certificate authentication. A real checkout/place/cancel through Traefik
passes (`acceptance-checkout-client-identity-smoke.log`). Original-CA TLS handshakes
inside the application network pass; the host antivirus substitutes its own
certificate on published loopback ports, so the external runner uses isolated
Linux networking without weakening trust. It keeps REST/WebSocket calls routed
through Traefik and mounts no CA private key.

The external suites have run against the deployed stack. Shipment/lifecycle and
both authenticated transport reads pass. Refund authentication failed because gRPC authentication interceptors were not
activated; STOMP rejected the realm's subjectless tokens. Both causes have been
repaired. All ten expanded external cases now pass, including four negative-token
cases, both cancellation/refund cases, lifecycle and live delivery/unsubscribe. The gRPC starter's security auto-configuration requires an explicit
AuthenticationManager. Both services now register it and reject missing/invalid
business-RPC identities while preserving infrastructure health probes. Focused
production-configuration tests use real RPC worker threads and verify caller
propagation, rejection and thread-context cleanup; both pass in
`grpc-authentication-wiring-final-verification.log`. External negative cases are
also required, rather than inferring authentication from an authenticated read.

The development realm also omitted Keycloak 25's default `basic` scope. Added
its `oidc-sub-mapper` and default assignments for every configured client. Real
HTTP/token checks show stable subjects, and STOMP CONNECT now succeeds
(`acceptance-stomp-subject-verification.log`). A new uniquely named probe realm
imported directly from the checked-in JSON verifies both users' subject, issuer,
roles and customer mapping, then is removed without resetting other realms
(`acceptance-fresh-realm-verification.log`).

The deployed notification image cannot load Kafka's native Snappy library on
Alpine; the gRPC payment image also crashed in native TLS. All six core runtime
Dockerfiles now use a glibc Jammy image. A redacted STOMP transport error handler
covers channel-interceptor failures that controller advice does not handle.
Its redaction test and the real-socket broker test pass in
`stomp-error-boundary-verification.log`. The isolated runtime images use freshly
packaged jars; they do not establish successful execution of the checked-in
multi-stage Docker build, which remains required by CI.

Production inventory/payment are constrained to one replica with stop-first
updates and rollbacks, matching their process-local stream registries. The
resolved Compose JSON passes the required topology gate; its adversarial scaling
and rollback regressions are included in all 36 passing Python tooling tests
(`tooling-final-verification.log`). Invalid pgBackRest indentation and undeclared
Kafka volumes were repaired. Explicit port overrides now remove inherited
Postgres/Redis/Kafka bindings and the ingress dashboard port. These configuration
checks do not establish production deployment or process-replacement acceptance.
See [stream topology](grpc-stream-topology.md).

All seventeen modules pass the explicit cross-platform LF formatting policy
(`formatting-portable-verification.log`). Certificate setup passes preservation,
mismatch/incomplete-input and invalid-name checks on Linux, plus first/repeat
execution under Windows Git Bash. The pinned collector configuration passes its
own image's validation after removing an unsupported metrics setting.

The previous complete default-reactor run was interrupted during payment tests
when Docker stopped. It is not a passing full verification. The authorized clean static-analysis/default run passes all 999 tests after
clearing generated-directory attributes and granting its required Docker access. External
acceptance passes all ten cases in `external-linux-journey-verification.log`;
`coverage/external-linux-evidence.zip` and JSON retain reports, source/test inputs,
six actual deployed JAR hashes and public configuration/certificate hashes. The complete reactor and external test-suite milestones are now accepted.
Read-only GitHub checks again found the supplied repository empty on November 6;
workflow execution and protected merge checks remain unverified. No remote
publication or branch-protection change is claimed.

## 2026-11-06 notification client package

The former unbuilt reference snippet is replaced by `clients/notifications`, an
ES module package with generated TypeScript declarations and pinned dependencies.
It refreshes credentials before expiry and on reconnect, shares subscriptions per
destination, supports independent idempotent unsubscribe handles and clears old
subscriptions when caller identity changes. State recovery is cancelled on stop
or connection replacement; generation checks suppress old callbacks. The native
STOMP URL is `/ws/websocket`, while the Java SockJS client uses `/ws`.

The simple broker has no receipts or durable replay. The package therefore treats
events as coalescible invalidations, serializes canonical API reads after connect
and hints, and periodically reconciles missed gaps. It bounds listeners, buffered
destinations, UTF-8 message size, retry delays and timeouts. Broker errors stop
retry; failure diagnostics contain codes rather than frames, tokens or exception
text. Applications must honor AbortSignal before committing fetched state.

All 17 real WebSocket/STOMP transport tests pass in
`notification-client-verification.log`, including offline convergence, independent
unsubscribe, credential refresh, identity change, cancellation, malformed and
multibyte oversized payloads, overflow, stalled reads and bounded failures.
`notification-client-dependency-verification.log` reports zero vulnerabilities.
The CycloneDX SBOM and `omp-notifications-0.1.0.tgz` are generated; the archive is
checked to contain only JavaScript, declarations, package metadata and README.
It has not been published to a registry. CI requires package tests, advisory
policy, SBOM and artifact generation. An additional deployed package test covers
actual issuer/native STOMP/order event recovery and foreign-owner denial; both cases pass in
`notification-client-deployed-verification.log`. The real issuer/native STOMP/Kafka/Redis
journey recovers a cancellation missed while offline; the actual ownership policy
rejects a fresh foreign-order subscription without delivering data. JUnit reports
assert 17 transport plus two deployed cases with zero failures/errors/skips.
`coverage/notification-client-evidence.zip` and JSON retain those reports, source
hashes, the distributable and SBOM. Only the isolated acceptance project is stopped
afterward; its volumes are retained.

## 2026-11-06 complete-reactor evidence and static-analysis enforcement

The authorized clean seventeen-module `-Pstatic-analysis --fail-at-end clean
verify` completed successfully in 31:14. All 999 discovered tests pass without
failures/errors/skips/missing suites. All ten executable jar/layer checks pass.
The stricter archive check then found two missing JetBrains annotation classes in
gateway's SpotBugs XML despite the plugin's green status. Gateway's static-analysis
profile now supplies the annotations; its three-module follow-up passes all
92 unit/in-process cases and eleven HTTP cases, including unchanged coverage gates.
All fourteen reports now contain zero findings/errors/missing classes. No application behavior or coverage threshold
was relaxed. `scripts/check_static_analysis.py` makes missing/error/empty analysis
fail CI; all 47 Python tooling regressions pass, including six static-analysis and five JUnit evidence-gate cases.

The earlier two full attempts were environment failures: OneDrive read-only
build directories prevented clean, and a sandboxed process lacked Docker
named-pipe access. Their logs are retained separately. The successful clean run
uses verified generated-output attribute repair and authorized engine access.
The successful clean command, gateway correction, current reports, 47 tooling
cases and ten package checks are retained in
`coverage/complete-default-reactor-evidence.zip` and its source/build-input JSON.
The test-suite baseline milestone is now checked in `TASKS.md`.

The documentation continuation adds an architecture/trust-boundary guide, API
catalogue and bounded development/incident procedures with resolved local links.
It corrects the stale inventory expiry claim and adds the three missing banking
and simulator service READMEs. The simulator README explicitly records its
currently incompatible callback example; that provider journey remains open.
Production restore/rotation/rollback procedures still require implementation and
executed evidence; these guides do not substitute for those acceptance records.

## 2026-11-06 initial publication and CI corrections

With explicit user approval, published the reviewed 1,035-file source snapshot
as `0fc72020ed53203214868f6534f8918edb96e8dc` to
`vamegah/order-management-platform/main`. Runtime environment files, generated
TLS identities, private keys, caches and local acceptance artifacts were excluded.
The [first default-branch run](https://github.com/vamegah/order-management-platform/actions/runs/42278291122)
passed notification-client, static-analysis, packaged-payment-journey and all ten
source Dockerfile image builds. Reports and the aggregate production SBOM were
uploaded by Actions. These results establish actual GitHub execution for those
jobs; the complete quality gate failed.

The initial publication whitelist omitted `contracts/soap/v1`, which caused seven
tooling errors and the SOAP parity failure. The corrected snapshot includes the
published XSD/WSDL and contract policy. All 47 tooling tests and SOAP parity pass
from an independently extracted source archive, so local untracked files cannot
mask this failure again. Linux first/repeat certificate setup and the wrapper's
no-unzip/checksum regressions also pass against the corrected archive.

The external job failed downloading Maven before its image build, despite all
ten independent image jobs succeeding. Wrapper downloads now prefer curl with
bounded retries, connection/total timeouts and visible errors; wget fallback is
also bounded. TLS verification and the pinned distribution checksum are retained.

Dependency-Check 13.0.0 failed before scanning because of its documented empty-key
API regression. The corrected configuration uses supported official NVD 2.0
annual/modified feeds without secrets and checks for updates on every scan.
CVSS >= 7 and scanner errors still fail. No successful vulnerability scan or
clean dependency inventory is claimed. Follow-up publication, complete default
and pull-request execution, and required branch checks remain open.

The automatic approval reviewer rejected the follow-up push because the user's
publication approval covered the original prepared snapshot only. The corrections
remain committed locally for separate review and approval before public upload.

## 2026-11-06 published baseline and dependency migration

The user subsequently approved the reviewed follow-up and further CI repairs.
Commits `ec5fcde` and `b5c95e4` are published on `main`; there is no remaining
publication-approval block. The [latest baseline run](https://github.com/vamegah/order-management-platform/actions/runs/42302324647)
passes every prerequisite except dependency-policy. This includes actual default
reactor and external deployed acceptance, SOAP/protobuf contracts, client tests,
static analysis and all ten image builds. Independently downloaded earlier CI
artifacts contain 999 passing Java cases and twelve passing external/client
cases with no failure/error/skip entries.

The official NVD feeds resolve the scanner's empty-key bug. Analysis now completes
and correctly rejects vulnerable versions under CVSS >= 7. No scan error or
vulnerability failure is ignored. Some older Spring fixes are enterprise-only,
so an isolated `ci/security-baseline` branch moves to the compatible Boot 4.1 /
Cloud 2025.1 generation and patched dependency families. The published baseline
is preserved while this migration is verified.

All production sources compile after moving Boot APIs, adapting unrestricted JPA
specifications and SOAP configuration, and updating JSON compatibility and layer
extraction. The first unit diagnostic exposed Feign codec configuration, missing
test HTTP infrastructure and the old starter's removed Security voter API. The
dedicated `shared/grpc` integration replaces only incompatible security/health
auto-configuration and restores metrics ordering; it does not add a server to
client-only applications or change mTLS/ownership requirements.

Focused acceptance passes all 36 shared-web cases (including three real HTTP /
codec precedence / optional-classpath cases) and three gRPC integration cases.
Real production interceptor discovery authenticates RPCs, rejects missing and
invalid credentials, clears worker context and records server timers. Channel
health tracks connectivity and its existing disable setting. Existing shared-web
85% and new transport-module 80% instruction gates pass. Logs are
`boot4-shared-integration-verification.log` (web passes; initial transport test
import fails) and `boot4-shared-grpc-verification.log` (transport passes).

The complete eighteen-module clean unit/package run passes all 776 cases without
failures, errors or skips in nine minutes. All ten executable packages pass real
Boot tools-mode launcher/layer extraction. Logs are
`boot4-complete-unit-package-verification.log` and `boot4-packaging-verification.log`.
Complete service/container, static, image, vulnerability and PR coverage acceptance remains
required on the migration revision; see [the migration scope](dependency-security-baseline.md).

## 2026-11-06 approved migration PR and verification repairs

The user explicitly approved publication of the reviewed 75-file migration and
its necessary verification fixes. Commit `2a8e9e5` is published on
`ci/security-baseline`; [draft PR #1](https://github.com/vamegah/order-management-platform/pull/1)
preserves `main`. The [first migration run](https://github.com/vamegah/order-management-platform/actions/runs/42310116688)
passes all ten source image builds, strict static analysis, client packaging and
exact-base SOAP/protobuf compatibility. Its remaining failures are diagnostic
evidence, not accepted verification.

The HTTP suites reproduce the upstream REST Assured 5.5/Groovy 5 failure. Both
REST Assured artifacts now select 6.0.1, and an independent real HTTP/JSON probe
passes on the service classpath. Runtime payment startup additionally exposes a
missing Jackson 2 auto-configuration dependency hidden by broader test fixtures;
that dependency is now explicit. The seven existing traced services include
Boot's dedicated OpenTelemetry integration and current collector/transport
property bindings. Product HTTP verification also checks incoming W3C trace
identity and actual Swagger UI resource routing.

The scanner's remaining findings are the bundled DOMPurify 3.4.16 in Swagger UI.
The selected 5.34.1 webjar has been inspected and contains the vendor-patched
3.4.17 in both JavaScript bundles. No suppression or policy reduction is added.
The real PR coverage comparison detects a one-instruction product decrease after
the JPA API replacement; an invalid-status predicate regression now checks the
fail-closed behavior and extends coverage without changing the comparison gate.
Focused container verification and a fresh complete workflow remain required.

The focused repair build subsequently passes all 156 shared/product cases:
37 shared-domain, 36 shared-web and 83 product cases (32 unit and 51 container
integration). No case fails, errors or skips; all three coverage policies pass.
The product checks include actual W3C trace identity and Swagger UI JavaScript
resource routing. Log: `boot4-product-http-migration-verification.log`.

During verification, PR #1 was merged externally as `9bc76d9`, and its source
branch was deleted. The published repair commit `9315979` is now reviewed in
[draft PR #2](https://github.com/vamegah/order-management-platform/pull/2).
Main protection is configured and independently verified: require the actual
GitHub Actions `quality-gate` check (app ID 15368), up-to-date branches and
administrator enforcement. Force pushes/deletion are disabled; no additional
approving-review policy is introduced. A complete successful repair workflow
and subsequent default-branch verification remain required.

## 2026-11-06 final startup and broker fixture repairs

The repair run `42312503607` passes the actual NVD policy across 211 dependencies
with no CVSS >= 7 findings, exact PR-base unit coverage, all fifteen complete
static reports, client, contracts and ten source image builds. Complete reactor
diagnostics identify only the duplicated payment HTTP catch-all chains and the
notification fixture's obsolete embedded-broker listener override; 948 other
executed cases pass, while the dependent platform suite remains absent.

Payment HTTP policy now has one catch-all chain in `PaymentSecurityConfig` and
the existing specific webhook chain. Its gRPC configuration retains the JWT
authentication manager and required bearer reader. The real RPC regression now
loads both production configurations together and passes valid/missing/invalid
credentials, health calls and worker-context cleanup. The complete changed
payment unit suite passes 222 cases. Ten focused provider cases also pass,
including two additional success/replay and decline contracts; measured unit
coverage over unchanged production classes exceeds the exact base ratio. A
clean two-revision CI measurement remains authoritative.

The notification fixture now uses the current embedded KRaft broker's default
compatible listeners and ephemeral ports. Its clean five-module verification
passes all 214 cases: 201 unit/in-process and thirteen PostgreSQL/Redis/Kafka/
HTTP/STOMP integration cases. No case fails, errors or skips; all four module
coverage policies pass. Log: `boot4-notification-broker-verification.log`.

PR #2 was closed externally without merging during the pause. Main remains at
`9bc76d9` with the required quality check, strict freshness and administrator
enforcement. The approved repair branch continues through a new draft review;
complete final-revision and subsequent default-branch acceptance remain open.

The `f55a3f6` workflow subsequently passes every prerequisite except the complete
reactor. Downloaded reports contain 779 unit and 231 integration cases: 1,009
pass, with one missing `TestRestTemplate` bean in the outbound test endpoint
fixture and no skipped cases. The twelve deployed, two independently packaged
and seventeen client cases all pass; the fresh PR-base comparison also passes.
Explicit Boot 4 test-client auto-configuration repairs the isolated fixture.
Its focused real HTTP/PostgreSQL/Kafka/Redis/subscriber case now passes in
`boot4-outbound-http-fixture-verification.log`; complete final-revision CI remains
required. PR #2 was reopened externally and its review state is preserved.

## 2026-11-06 accepted security baseline and lifecycle continuation

The [complete PR workflow](https://github.com/vamegah/order-management-platform/actions/runs/42334961223)
passes at `e25735e`. Downloaded XML independently confirms all 1,010 reactor
cases (779 unit and 231 integration), twelve deployed service/client cases,
two packaged payment-to-webhook cases, seventeen client transport cases and
thirty-two contract-job cases without failures, errors or skips. The packaged
cases also execute in the full reactor; the separate job is an independent
verification scope. Exact-base coverage passes for fourteen production modules,
all fifteen complete static reports are clean, and all ten source images build.
The NVD job analyzes 211 dependencies with no CVSS >= 7 findings; SBOM generation
and the aggregate `quality-gate` pass.

All eight artifact sets, workflow logs, exact source commit/tree, independently
verified main protection and file hashes are archived in
`coverage/security-baseline-ci-evidence.zip` and JSON. The archive SHA-256 is
`3e38bd7d7fa9026915ef64bcf3ab76ad24c5047f1f62ad26dace60167c42360f`.
PR #2's review description contains the actual accepted results. The user then
explicitly approved merging and default-branch verification. The protected merge
is `1a38671a39e20b9c91ac6f498f50027360fd9f29`; its
[main workflow](https://github.com/vamegah/order-management-platform/actions/runs/42336296845)
also passes every prerequisite and `quality-gate`. Downloaded reports confirm
the same 1,010 reactor cases and successful deployed, packaged, client and
contract scopes without failures, errors or skips. The scan, fifteen complete
static reports and ten image builds pass. Its seven artifact sets, full logs and
exact source tree are retained separately in
`coverage/security-baseline-main-ci-evidence.zip` and JSON; archive SHA-256 is
`d7767abdb9c31a82bf1e4e9783e16357600045989dd069ea04dc6ae49ba8da01`.
The PR-only exact-base coverage artifact remains in the PR archive. No status
or freshness policy was bypassed. Baseline CI and quality-gate tasks are now
accepted; production release and the remaining feature requirements stay open.

The next section's local work is isolated on `implementation/reservation-cleanup`
from the merged baseline. Reservation cleanup previously released an unchecked
reference and ignored the result. It now validates reservation/order identity
before mutation and before acknowledgment, accepts only released/expired final
states, and retains unsupported/unresolved outcomes for retry. Regressions cover
foreign identities, already resolved holds, unsupported states, invalid release
responses, expiry races and a lost response followed by an idempotent retry.
The clean seven-module changed-order/shared build subsequently passes all 331
cases: 289 unit cases (including seven protobuf compatibility cases) and all
42 order integration cases, with no failures, errors or skips. All twenty new
cleanup regressions pass. Six complete static reports contain zero findings,
errors or missing classes, and all five coverage policies pass. Order coverage
is 81.54% against its unchanged 80% gate. The order evidence checker independently
accepts all 185 order cases. Source-bound reports are retained in
`coverage/order-reservation-cleanup-evidence.zip` and JSON; log:
`order-reservation-cleanup-verification.log` (7:55, exit 0). This local follow-up
is not included in the accepted migration workflow above.

## 2026-11-06 cleanup publication and checkout correlation

The user approved publication and verification fixes for the reviewed cleanup
commit `2f9de5b`. [Draft PR #3](https://github.com/vamegah/order-management-platform/pull/3)
publishes that exact nine-file follow-up. Its
[full workflow](https://github.com/vamegah/order-management-platform/actions/runs/42341917633)
passes every prerequisite and `quality-gate`. Downloaded reports contain all
1,030 reactor cases (799 unit and 231 integration), twelve deployed service/client
cases, both packaged journeys, seventeen client cases and thirty-two contract-job
cases without failures, errors or skips. All fifteen complete static reports are
clean, the NVD policy passes across 211 dependencies, and all ten images build.
The coverage artifact records 779 base
and 799 head unit cases, with all fourteen production modules passing. Order
unit coverage increases from 3,411 covered / 2,816 missed to 3,491 / 2,808.
All eight artifacts, full logs and exact source tree are retained separately in
`coverage/reservation-cleanup-ci-evidence.zip` and JSON; archive SHA-256 is
`62b06fa0932e10375765a8ce377ba24819337ba76e3f703837581620a715ca12`.
The user explicitly approved marking the draft ready, merging and verifying main.
PR #3 is merged as `82732be5b3a1aa4f50e32ac974057c47d4eabe42`; its
[default-branch workflow](https://github.com/vamegah/order-management-platform/actions/runs/42343178370)
also passes every prerequisite and `quality-gate` under the unchanged freshness
and administrator policy. Downloaded reports confirm all 1,030 reactor cases and
the same successful deployed, packaged, client and contract scopes, without
failures, errors or skips. The NVD policy, fifteen complete static reports and
ten source images pass. Its seven artifact sets, full logs and exact source tree
are retained in `coverage/reservation-cleanup-main-ci-evidence.zip` and JSON;
archive SHA-256 is
`3b217efaa8ae0484d1868f3c4bc46a3e823c407ffa1d63f0ac8fddb90a2817cd`.
The PR-only exact-base coverage artifact remains in the PR archive.

The next local branch, `implementation/checkout-correlation`, is separate from
PR #3. Inventory reservation replies must match the requested order, SKU,
warehouse, quantity and active state before returning a durable reference.
Malformed identities and duplicate reservation IDs across order lines fail as
downstream errors before payment/acceptance. Real in-process RPC regressions
exercise mismatched replies, and a partial-checkout case requires compensation
of only the verified first hold. The clean seven-module changed-order/shared
verification passes all 347 cases: 305 unit and 42 integration, including sixteen
new regressions. All five coverage gates pass; order coverage is 81.84% against
its unchanged 80% gate. Six complete static reports contain zero findings/errors/
missing classes. The order evidence checker accepts all 201 order cases. Reports
are retained in `coverage/checkout-reservation-correlation-evidence.zip` and JSON;
log: `checkout-reservation-correlation-verification.log` (7:37, exit 0). These
local changes are not part of PR #3's workflow or publication approval.

## 2026-11-06 PostgreSQL reservation-expiry acceptance

The next local continuation adds three actual PostgreSQL cases to
`InventoryPersistenceIT`. They execute the existing production expiry worker
and transaction proxies. Oldest-first bounded batches exclude recent/terminal
holds and conserve stock. A real outbox check-constraint failure rolls back
stock/hold changes; after removing the injected fault, retry commits exactly once.
A competing worker skips a row locked by another transaction, expires the
available hold and later reclaims the unlocked row without double release.
Completion/failure counters and committed outbox counts are asserted.

The clean five-module inventory/shared static-analysis build passes all 118
cases: 110 unit (37 inventory and 73 shared) and eight PostgreSQL integration,
without failures, errors or skips. Four complete static reports contain zero
findings/errors/missing classes. All three coverage policies pass; inventory
coverage is 91.35% against its unchanged 80% gate. The inventory evidence checker
independently accepts all 45 service cases. Reports and exact source are retained
in `coverage/inventory-expiry-acceptance-evidence.zip` and JSON; log:
`inventory-expiry-acceptance-verification.log` (4:21, exit 0).

No production expiry behavior or enablement changes are included. The worker
stays opt-in. Process-crash/deployed recovery, expired accepted-order/payment
coordination and operator retry acceptance remain open in `TASKS.md`. This local
acceptance work is separate from the merged cleanup revision and its workflow.

## 2026-11-07 checkout/expiry publication and recovery discovery

The user approved publishing the reviewed checkout/expiry follow-up and its
verification fixes. [PR #4](https://github.com/vamegah/order-management-platform/pull/4)
passes its [complete workflow](https://github.com/vamegah/order-management-platform/actions/runs/42344605687)
at `c0e8b62`: all 1,049 reactor cases (815 unit and 234 integration), twelve
deployed service/client cases, both independently packaged payment journeys,
seventeen client cases and thirty-two contract-job cases, without failures,
errors or skips. All fourteen exact-base unit coverage comparisons pass; order
instruction counts improve from 3,491 covered / 2,808 missed to 3,566 / 2,801.
All fifteen complete static reports are clean, the NVD policy accepts 211
dependencies without CVSS >= 7 findings, and all ten source images build.

The eight artifact sets, full workflow logs, exact reviewed source and proof that
GitHub's tested merge reference has the same source tree are retained in
`coverage/checkout-expiry-ci-evidence.zip` and JSON. Archive SHA-256:
`14689e63fa72ec246214a72f7ae2deb18190a99fa2189a0139b41c93860a31c6`.
The user separately approved marking the draft ready, merging and verifying main.
PR #4 is merged as `4fa2123fa0a578501693179f1ea7ea6138b90561` under the unchanged
required GitHub Actions check, strict freshness and administrator enforcement.
Its [default-branch workflow](https://github.com/vamegah/order-management-platform/actions/runs/42380679185)
also passes every prerequisite and `quality-gate`. Downloaded reports confirm
all 1,049 reactor cases and the same deployed, packaged, client and contract
scopes without failures, errors or skips. Fifteen complete static reports, the
211-dependency NVD policy and all ten source images pass. Its seven artifact
sets, full logs and exact merged source are retained separately in
`coverage/checkout-expiry-main-ci-evidence.zip` and JSON; archive SHA-256:
`90f267949c1b1091d492d3e041238c430b36e02cc7b07d85572414752f6c9200`.
The PR-only coverage comparison remains in the PR archive.

The next local branch, `implementation/recovery-discovery`, validates complete
inventory/payment discovery results before enqueueing each result's resources.
Every resource must have a distinct non-null identity and match the journal
attempt's order; each result is bounded to the documented 100-resource contract.
Invalid scans remain retryable and increment the existing failure metric. No
prefix of a failed result is queued, while already verified reservations survive
a later payment-lookup failure and unrelated journal attempts continue.
The clean seven-module order/shared static-analysis build passes all 360 cases:
318 unit (172 order and 146 shared, including seven protobuf cases) and all 42
PostgreSQL/HTTP integration cases, without failures, errors, skips or missing
suites. All thirteen new real-RPC regressions pass. Six complete static reports
contain zero findings/errors/missing classes, and all five existing coverage
policies pass. Order instruction coverage is 82.77% against its unchanged 80%
gate. The evidence checker independently accepts all 214 order-service cases.
Reports and exact source are retained in
`coverage/checkout-recovery-discovery-evidence.zip` and JSON; log:
`checkout-recovery-discovery-verification.log` (8:44, exit 0).
This work does not enable recovery or expiry, provision a workload identity,
repair historical rows or establish deployed crash/compensation acceptance.

## 2026-11-07 recovery discovery publication

The user approved publication and verification fixes for recovery-discovery
commit `53708b6`. [PR #5](https://github.com/vamegah/order-management-platform/pull/5)
passes its [complete workflow](https://github.com/vamegah/order-management-platform/actions/runs/42382106790):
all 1,062 reactor cases (828 unit and 234 integration), twelve deployed
service/client cases, two packaged payment journeys, seventeen client cases and
thirty-two contract-job cases, without failures, errors or skips. All fourteen
exact-base unit coverage comparisons pass; order counts improve from 3,566
covered / 2,801 missed to 3,686 / 2,755. All fifteen complete static reports are
clean, the NVD policy accepts 211 dependencies without CVSS >= 7 findings, and
all ten source images build. The eight artifact sets, full logs, exact source
and tested-merge source proof are retained in
`coverage/recovery-discovery-ci-evidence.zip` and JSON; archive SHA-256:
`54c8a6d6efdcca16bd51d4f3113780bdd53d4c26acb246d6c087e7486e865a54`.

The user separately approved marking the draft ready, merging and verifying
main. PR #5 is merged as `3525418a668da43ae01965aed5c7b72c9231f624` under the
unchanged required GitHub Actions check, strict freshness and administrator
enforcement. Its [default-branch workflow](https://github.com/vamegah/order-management-platform/actions/runs/42384161158)
also passes every prerequisite and `quality-gate`. The initial image job failed
during BuildKit setup because a Docker CDN connection reset interrupted its
download before the application build. After the other jobs completed
successfully, a targeted failed-job retry passed the image build and aggregate
gate on the same source revision. No application change was needed.

Downloaded main reports confirm all 1,062 reactor cases and the same deployed,
packaged, client and contract scopes without failures, errors or skips. All
fifteen complete static reports are clean, the NVD policy passes across 211
dependencies, and all ten source images build. Its seven artifact sets, exact
merged source, both attempts' full workflow logs, original job/run metadata and
retry request are retained in `coverage/recovery-discovery-main-ci-evidence.zip`
and JSON; archive SHA-256:
`ab5960d121d71317ab0bb6ad19d258d4fd100338f0b834028dd4460a6480fbc4`.
The PR-only coverage comparison remains in the PR archive.

## 2026-11-07 payment checkout correlation

The payment continuation was verified in
`.scratch/checkout-payment-worktree` on `implementation/checkout-payment-correlation`
at `78c07f4`, from source revision `53708b6`. The publication candidate is on
`implementation/checkout-payment-followup`, based on merged main `3525418`.
Its runtime files, tests and build inputs match the verified snapshot; only
TASKS.md and this report record subsequent parent verification.
The payment client previously retained the
returned payment ID without checking that its owner or amount matched checkout.
It now verifies order/customer identity, currency and a bounded positive decimal
amount before returning a durable reference. Numeric comparison permits equivalent
decimal representations. Only pending or confirmed-with-reference results are
usable. The confirmed case preserves the payment service's existing idempotent
replay behavior, which returns the original payment's current state.

Malformed or mismatched replies fail through the existing generic downstream
HTTP 502 contract before payment reference retention or aggregate acceptance.
Compensation queues only verified inventory holds after a rejected payment reply;
the journal retains discovery of unknown side effects. A verified payment still
enters compensation if the local acceptance transaction fails.

The clean seven-module order/shared static-analysis build passes all 389 cases:
347 unit (201 order and 146 shared) and all 42 PostgreSQL/HTTP integration cases,
without failures, errors, skips or missing suites. All twenty-nine new real-RPC
regressions pass, including decimal equivalence, confirmed replay, invalid
identities/owners/amounts/states, rejected-reply compensation and compensation
after a failed local save. Six complete static reports contain zero findings,
errors or missing classes. All five existing coverage policies pass; order
instruction coverage is 83.15% against its unchanged 80% gate. The evidence
checker independently accepts all 243 order-service cases. Source-bound reports
are retained in `coverage/checkout-payment-correlation-evidence.zip` and JSON;
log: `checkout-payment-correlation-verification.log` (8:25, exit 0).

This local verification is separate from recovery-discovery publication;
complete CI for the payment follow-up remains required before merging it.
Historical-row repair, deployed workload identity, process-crash compensation
and accepted-order/payment expiry coordination remain open. No recovery or
expiry worker is enabled by this change.

## 2026-11-07 historical reservation reference repair

The next core-lifecycle continuation adds an administrator-only inspection and
repair resource for historical NULL reservation pointers. Flyway V11 adds a
durable per-line audit without rewriting historical rows. A complete plan must
identify every order line and distinct existing inventory reservations; existing
references cannot be replaced. Only DRAFT, PLACED and CONFIRMED orders can receive
missing pointers. The inventory identity, order, SKU, quantity and active state
must match before the write transaction begins. No stock is allocated or mutated.

The short JDBC transaction locks the root order, rechecks its version, state
and complete binding plan, restores NULL references, writes the audit and bumps
the aggregate version once. A failed audit rolls back the reference and version.
This also fences JPA transitions that loaded the earlier version. Matching
already-bound plans are idempotent, including a lost-response retry with an old
version: they add no audit, inventory call or version increment. Metadata repair
preserves order state/history and emits no new public domain event. The verified
operator subject and bounded reason are retained without storing a bearer token.
See [the operator procedure](order-reservation-repair.md).

Clean seven-module order/shared `-Pstatic-analysis spotless:apply clean verify`
passes all 435 cases: 384 unit (238 order and 146 shared) and 51 PostgreSQL/HTTP
integration cases, with no failures, errors, skips or missing suites. The 46 new
cases cover malformed/ambiguous plans, downstream mismatches, forbidden states,
transaction boundaries, durable audit/idempotence, complete multi-line repair,
audit-failure rollback, both concurrency directions, administrator policy and
foreign-reservation rejection. Six complete static reports contain zero findings,
errors or missing classes. All five existing coverage policies pass; order
instruction coverage is 84.59% against its unchanged 80% gate. The evidence
checker accepts all 289 order-service cases. Exact source, reports and the full
successful log are retained in `coverage/legacy-reservation-repair-evidence.zip`
and JSON; log: `legacy-reservation-repair-verification.log` (9:13, exit 0).

The initial build exposed an authorization-test assumption: invalid JSON is
validated before controller method authorization. The denial case now submits a
well-formed plan and proves HTTP 403; administrator validation separately proves
HTTP 400. A subsequent clean attempt stopped on Windows read-only attributes in
generated target directories. The local Maven helper now resolves the requested
worktree and clears those attributes only inside its generated targets; the
fresh corrected build above passes. Neither correction changes runtime policy.

This work does not invent missing inventory history, establish warehouse
provenance, repair terminal orders or coordinate inventory state across services.
A separate clean unit-only package/report build passes all 384 cases in 3:09.
All five focused module comparisons pass the unchanged CI instruction policy
against the accepted main source. Order counts improve from 3,819 covered/2,727
missed to 4,250/2,994. Unit-only reports and baseline source proof are retained
in `coverage/legacy-reservation-repair-unit-evidence.zip` and JSON; archive
SHA-256: `0ed729865cf99bd24ee1dbb53c47e9436e3f9e541dffbf28eef51885809a90bc`.
The combined archive SHA-256 is
`5c657cbeb4821d5e7aa0a252e62254b0f5a62c00051732aa80aafafd52ac2eba`.
The verified source is `5c3f3fa` in the isolated repair worktree; its runtime,
tests and build inputs are identical in the main-workspace review branch, where
only documentation records subsequent verification. Complete PR verification,
including fourteen-module unit comparison, remains required before merging.
Deployed identity, process-crash compensation and
accepted-order/payment expiry coordination remain open. Workers stay disabled
by default, and the broader lifecycle checkboxes remain unchecked.

## 2026-11-07 payment correlation publication acceptance

With explicit publication approval, payment correlation `7d782f4` was published
as [draft PR #6](https://github.com/vamegah/order-management-platform/pull/6).
Its [complete workflow](https://github.com/vamegah/order-management-platform/actions/runs/42386392989)
passes all 1,091 reactor cases (857 unit and 234 integration), twelve deployed
service/client cases, two independently packaged payment-to-webhook cases,
seventeen client transport cases and 32 contract-job cases. Required reports
contain no failures, errors or skips. All fourteen exact-base unit coverage
comparisons pass, all fifteen complete static reports are clean, the NVD policy
passes across 211 dependencies and all ten source images build. The tested PR
merge reference has the same source tree as the reviewed revision. Reports,
workflow logs and exact source are retained in
`coverage/checkout-payment-ci-evidence.zip` and JSON; archive SHA-256:
`183a82b4cbfb65155508bb43b1c08243c190879ded8b3984bc7f793acaead6d9`.

After separate user approval, PR #6 was marked ready and merged as
`2e038f8be5e9f18cb5a4750f26dfd4d28daa1cd4` under unchanged strict freshness,
administrator enforcement and the required quality gate. The
[complete default-branch workflow](https://github.com/vamegah/order-management-platform/actions/runs/42388231306)
passes every gate on its first attempt. Downloaded reports confirm the same
1,091 reactor cases and deployed/packaged/client/contract scopes, fifteen clean
static reports, accepted NVD policy and ten image builds. All seven artifact
sets, full workflow logs and the exact merged source are retained in
`coverage/checkout-payment-main-ci-evidence.zip` and JSON; archive SHA-256:
`f88b65758917affec6db7ade41aa0ae9c935b7424d45549c7ceecd49185dd317`.
PR-only coverage comparison remains in the PR archive. This acceptance does not
publish the separate historical repair work or close deployed recovery tasks.

## 2026-11-07 historical repair PR acceptance

With explicit publication approval, historical repair `06d056d` was published as
[draft PR #7](https://github.com/vamegah/order-management-platform/pull/7).
Its [complete workflow](https://github.com/vamegah/order-management-platform/actions/runs/42389856101)
passes all 1,137 reactor cases (894 unit and 243 integration), twelve deployed
service/client cases, two packaged payment-to-webhook cases, seventeen client
transport cases and 32 contract-job cases. Required JUnit evidence has no
failures, errors or skips. All fourteen exact-base unit coverage comparisons
pass; GitHub's tested merge reference has the reviewed source tree. All fifteen
complete static reports are clean, the NVD policy passes across 211 dependencies
and all ten source images build. Reports, full logs and exact source are retained
in `coverage/legacy-reservation-repair-ci-evidence.zip` and JSON; archive SHA-256:
`bcda07d4dc5e2cc11af172beb02e881eb9a90355bb26c6d194534a3fdbc1092b`.
After separate user approval, PR #7 was marked ready and merged as
`d17fbacd274b1c224ef9929ea0d55b8f7f1c8355` under the unchanged required quality
gate, strict freshness and administrator enforcement. Its
[complete default-branch workflow](https://github.com/vamegah/order-management-platform/actions/runs/42414034870)
passes every gate on its first attempt. Downloaded reports confirm all 1,137
reactor cases and the same deployed/packaged/client/contract scopes without
failures, errors or skips. All fifteen complete static reports are clean,
the NVD policy passes across 211 dependencies and all ten source images build.
The seven artifact sets, exact merged source, full workflow logs and protected
merge evidence are retained in
`coverage/legacy-reservation-repair-main-ci-evidence.zip` and JSON; archive SHA-256:
`8806209a8bb6a0450c06be94af0c0d684a969d8c597e80d0f354331069efd4ce`.
PR-only coverage comparison remains in the PR archive. This acceptance excludes
the separate identity continuation below and does not close deployed recovery.

## 2026-11-07 scoped recovery identity continuation

The next isolated local branch, `implementation/checkout-recovery-identity`,
supplies a development-only `order-recovery` service account without customer
claims or administrator roles. Only its dedicated scope permits cross-order
recovery; ordinary development clients cannot request that scope. Explicit
inventory/payment audiences accompany the scope. Browser/password grants and
full-scope access are disabled for the workload client.

The standard-library projector reads a bounded credential file, requests a
short-lived client-credentials JWT, checks issuer/client/scope/audience/lifetime
metadata and atomically replaces a private token file. It rejects redirects,
oversized or unusable responses, and plaintext endpoints unless explicitly
configured for isolated development. Failed exchanges preserve the previous
projection, and logs contain neither credentials, tokens nor issuer bodies.
Downstream JWT verification remains authoritative. The projector reloads client
credentials during rotation; the optional overlay mounts its directory read-only
so replacements become visible to the existing worker.

All 57 Python tooling cases pass, including ten new HTTP/file cases with bounded
response handling, credential/token rotation, denial behavior, secret-free
diagnostics and failed atomic replacement. The portable issuer acceptance script
starts a fresh, labelled Keycloak 25 container and passes five checks: scoped
issuance without owner/admin claims, distinct renewed tokens, invalid-credential
rejection, ordinary-client scope denial and an issued signing-key identity that
matches the issuer's published keys. The fixture removes only its own container
and temporary credentials. Matching a published key is not a cryptographic
downstream acceptance test.

Five read-only Compose 2.39.4 checks prove disabled defaults, explicit checkout
recovery enablement, a read-only directory mount without implicit path creation,
preservation of all other service/environment settings and rejection of missing
projection configuration. Workflow YAML parses and requires the new
`workload-identity` job through the existing protected aggregate gate. The new
job runs the portable issuer fixture and retains its report; complete CI for
this follow-up remains required after separate publication approval.

Source-bound reports, logs and exact source are retained in
`coverage/recovery-workload-identity-evidence.zip` and JSON. The
[operator procedure](order-recovery-identity.md) explains UID/file permissions,
supervision, existing-realm updates, explicit enablement and safe disablement.
Production workload credentials/secret management, downstream JWT/mTLS behavior,
process-crash recovery and accepted-order/payment expiry compensation remain
open. No recovery or expiry worker is enabled by default, and the parent
lifecycle tasks remain unchecked.

The verified identity source is `0cdff48` in its isolated worktree; its archive
SHA-256 is `2e9057e0a5a9f9ecb3a78fd778f31cfe85cf42b7de51b621c96bc69294e9356b`.
The publication review branch is based on accepted main `d17fbac`. Runtime,
tests, realm, Compose and CI inputs match the verified source exactly; only
TASKS.md and this report record subsequent parent acceptance. Complete workflow
verification, including the new issuer gate, remains required after publication.

The complete [PR #8 workflow](https://github.com/vamegah/order-management-platform/actions/runs/42416321108)
subsequently passes all 1,137 reactor cases (894 unit and 243 integration), twelve
deployed service/client cases, two packaged payment-to-webhook cases, seventeen
client transport cases and 32 contract cases. All fourteen exact-base unit
coverage comparisons, fifteen complete static reports, the 211-dependency NVD
policy and all ten image builds pass. The new required issuer job passes five
checks bound to its three exact tested source files without recording credentials.

With separate user approval, PR #8 is merged as `3fb7e4a` after verifying strict
freshness, the GitHub Actions quality gate and administrator enforcement. Its
[complete main workflow](https://github.com/vamegah/order-management-platform/actions/runs/42418181493)
passes every gate on the first attempt with the same 1,137 reactor cases and five
issuer checks. Exact-source PR reports are archived in
`coverage/recovery-workload-identity-ci-evidence.zip` and JSON, SHA-256
`2bb34a41d47e2cab95e6dc61614ffaf1458dcbef569df4e9b8ecb5f9e40a76f2`.
Main reports, logs and merge/protection records are retained separately in
`coverage/recovery-workload-identity-main-ci-evidence.zip` and JSON, SHA-256
`a133d0f91b57124a02047234d1bcf4d17a2cc89d5f67f2f2375ee65a4b1dbeb4`.
Deployed downstream JWT/mTLS acceptance, process-crash compensation and production
identity/secret provisioning remain open.

## Historical orphan recovery admission (2026-11-07)

An administrator can now submit a reviewed historical order UUID to the existing
checkout recovery journal. The admission stores the verified operator subject,
bounded reason and timestamp in a new Flyway V12 audit table. It creates no
aggregate, remote resource or cleanup task and makes no RPC. Existing discovery
and cleanup still validate downstream ownership and resource states.

Existing aggregates in every lifecycle state and all normal checkout attempts
are excluded. The unique journal row serializes admission with checkout start;
normal acceptance rejects an admitted recovery attempt. Journal creation and its
audit commit together, and an audit failure rolls both back. Repeated admission
preserves the first audit, current recovery status, active lease and backoff.
Recovery sees no unaudited, uncommitted admission.

The complete clean order/shared build passes all **480 cases**: 408 unit and
72 PostgreSQL/HTTP integration, with no failures, errors or skips. This includes
45 new regressions: 18 service policy cases, six HTTP contract cases and 21
database/authenticated HTTP cases. Real PostgreSQL covers all existing order and
journal states, competing operators, a race with normal checkout, audit rollback,
retry and worker visibility. The full application verifies 401/403, bounded
reasons, idempotent admission and conflicts using the real Flyway schema.

A malformed UUID regression first reproduced HTTP 500. A specific typed-parameter
exception mapping now returns the stable 400 problem detail `Invalid request
parameter`; both standalone and authenticated application HTTP checks pass.
All six complete static reports are clean. Existing coverage gates are unchanged;
order instruction coverage is **85.04%** against its 80% gate. Exact-source
reports and logs are retained in `coverage/orphan-recovery-adoption-evidence.zip`
and JSON. A separate clean unit-only build passes all 408 cases and all five
focused comparisons using the unchanged CI instruction policy. Order instruction
counts are 4,250 covered/2,994 missed at the tested baseline and 4,450/3,021 at
head. The baseline source tree equals accepted main `3fb7e4a`. Unit-only reports
are retained in `coverage/orphan-recovery-adoption-unit-evidence.zip` and JSON;
complete fourteen-module PR comparison and full follow-up CI remain required.

The [operator procedure](order-orphan-adoption.md) provides a bounded, read-only
cross-ledger candidate query and requires review of each original UUID. Older
writers must first adopt journal-first checkout. Missing references on existing
cancelled orders, complete historical ledger discovery, production identity,
deployed JWT/mTLS/process-crash compensation, committed-stock handling and
accepted-order/payment expiry coordination remain open. No worker is enabled by
default, and the parent lifecycle task remains unchecked.

With user approval, this feature is published in
[PR #9](https://github.com/vamegah/order-management-platform/pull/9).
The verification follow-up adds four required deployed HTTP cases using the real
issuer and ingress: administrator policy, invalid requests without admission,
verified subject/first-audit retention without aggregate creation, and rejection
of a test-owned existing order without changing its payment/reservation. That
order is cancelled afterward. Runtime, migration and focused test sources are
unchanged from the archived 480-case local build.

The complete [PR workflow at `8ffbf83`](https://github.com/vamegah/order-management-platform/actions/runs/42423657218)
passes all 1,182 reactor cases (918 unit and 264 integration), sixteen deployed
service/client cases (fourteen Java and two client), two packaged payment-to-webhook
cases, seventeen client transport cases and 32 contract cases. All fourteen
exact-base unit coverage comparisons, fifteen complete static reports, the
211-dependency NVD policy, ten image builds and five isolated issuer checks pass.
Exact-source reports and logs are retained in
`coverage/orphan-recovery-adoption-ci-evidence.zip` and JSON, SHA-256
`f43d8197bb8433c1adc934a99081a90a1f8301e5e7c50dc461e4b22882a99f92`.
The superseded initial workflow was cancelled after the verification follow-up;
its incomplete run is not acceptance evidence.

With separate user approval, PR #9 is marked ready and merged as `a8afa7d` after
verifying strict freshness, the GitHub Actions quality gate and administrator
enforcement. The [complete main workflow](https://github.com/vamegah/order-management-platform/actions/runs/42425432635)
passes every gate on its first attempt with the same counts. Main reports, logs,
exact source and merge/protection records are retained separately in
`coverage/orphan-recovery-adoption-main-ci-evidence.zip` and JSON, SHA-256
`2690e426d3f7bcf46da4cd1ca9b640c2d8eda25a997b5655add9790d58d128f3`.
The parent reconciliation task remains unchecked: complete historical ledger
discovery, downstream workload/crash compensation, production credentials and
accepted-order expiry/payment coordination remain separate acceptance work.

## Recovery workload transport acceptance specifications (2026-11-07)

The next isolated follow-up adds five required `RecoveryTransportIT` cases to
the existing external e2e profile. Each uses the committed development realm;
the real token projector writes its client-credentials JWT to a private
temporary file. The clients preserve the original CA, hostname verification,
client certificate and per-RPC deadlines. The suite explicitly rejects a
plaintext test configuration.

The cases specify exact discovery of a test-owned order's persisted reservation
and payment, renewed projection acceptance on existing TLS channels, rejection
of a changed signature with otherwise valid workload claims, denial of ordinary
administrator/customer tokens at both scoped discovery methods, and denial of
workload access to administrator orphan admission. The owned order is cancelled
in cleanup. Admission-only UUIDs have no remote resources and belong to the
isolated test database. Neither tokens nor credentials appear in child-process
diagnostics or equality assertions.

The four-module `spotless:apply test-compile -DskipTests` build succeeds in
`recovery-transport-acceptance-compile.log`. This is compilation evidence only:
none of the five deployed cases has executed locally. The unchanged evidence
checker discovers `RecoveryTransportIT` and rejects its absent Failsafe report.
The fresh-stack CI job already provides Python 3.12, the updated realm and mTLS;
custom caller images must also supply Python (`E2E_PYTHON` selects its executable).
With publication approval, [PR #10](https://github.com/vamegah/order-management-platform/pull/10)
passes its [complete workflow at `a8385f6`](https://github.com/vamegah/order-management-platform/actions/runs/42427073174)
on its first attempt: all 1,182 reactor cases (918 unit and 264 integration),
21 deployed service/client cases (nineteen Java and two client), two packaged
payment-to-webhook cases, seventeen client transport cases and 32 contract
cases. All fourteen exact-base unit coverage comparisons have unchanged
instruction counts. Fifteen complete static reports, the 211-dependency NVD
policy, ten image builds and five isolated issuer checks pass. All five new
transport cases execute with no failures, errors or skips.

With separate user approval, PR #10 is marked ready and merged as `b93a582`
after checking strict freshness, the required GitHub Actions gate and
administrator enforcement. Its [complete main workflow](https://github.com/vamegah/order-management-platform/actions/runs/42428663795)
passes every gate on its first attempt with the same counts. The default
reactor continues excluding the e2e tag.

This follow-up changes tests and documentation only. It does not enable recovery
or expiry workers or establish process-crash/lease recovery, invalid client
certificate rejection, production issuer/secret rotation, complete historical
ledger discovery or accepted-order expiry/payment coordination. The parent
lifecycle tasks remain unchecked.

## Workspace recovery and durable worker failure specifications (2026-11-07)

After accidental workspace deletion, the restored checkpoint was preserved in
a source archive and local Git branch. Accepted main `b93a582` was recovered
in the original OneDrive workspace. Main's completed reports and logs are
reacquired from GitHub and bound to its exact published Git source in
`coverage/recovery-transport-restored-main-ci-evidence.zip` and JSON. This
recovered archive has a new digest; its current protection record is separate
from the original premerge records lost with the workspace.
Its SHA-256 is
`b9e8dd346749ea03be110afaedd330876b50b3ec6ac89c47cbc7c0c5d9ab391f`.

The unpublished worker fixture was reconstructed with explicit JUnit imports
and isolated test configuration. Ten `CheckoutRecoveryWorkerIT` cases combine
the actual worker, proxied journal and cleanup queue, V7/V8 schema and PostgreSQL
with generated fault-injecting RPC endpoints. Partial payment discovery retains
verified reservation cleanup. A later tick discovers and cancels the pending
payment without repeating completed mutations. A release that changes state
but loses its reply leaves a durable retry; a fresh worker object observes the
released state and acknowledges the task. Other cases check live lease
exclusion, stale completion fencing, malformed token replacement and six
accepted active order states.

Assertions inspect durable rows and recovery/cleanup metrics, forwarded identity
and RPC execution outside transactions. Eligibility is advanced explicitly in
the database and ticks are called directly. Synthetic credentials and controlled
RPC endpoints keep these checks separate from the deployed issuer/mTLS suite.
Process termination, scheduler timing, actual elapsed lease recovery and full
downstream failures remain open. No worker is enabled by default.

The reconstructed seven-module build passes formatting, compilation and all
three existing targeted worker unit cases without failures, errors or skips
(`recovered-worker-compilation-and-unit.log`, 13:24, exit 0). The existing source
evidence checker requires the new Failsafe suite. The ten new PostgreSQL cases
remain unexecuted locally because the Docker engine is unavailable; CI execution
is required after separate publication approval. Source-bound local reports,
logs and exact source are retained in
`coverage/recovered-worker-failure-acceptance-local-evidence.zip` and JSON.
Full CI is expected to execute 1,192 reactor cases and the unchanged 21 external
cases. No passing integration result is claimed for this reconstructed fixture.

## Durable worker PR and default-branch acceptance

[PR #11](https://github.com/vamegah/order-management-platform/pull/11) subsequently
executes all ten reconstructed worker cases. Its complete workflow passes 1,192
reactor cases (918 unit and 274 integration), 21 deployed service/client cases,
two packaged payment-to-webhook cases, seventeen client transport cases and
32 contract-job cases without required failures, errors or skips. All fourteen
exact-base unit coverage comparisons pass with identical production instruction
counts; order remains 4,450 covered/3,021 missed. All fifteen static reports,
211-dependency vulnerability policy, ten image builds and five isolated issuer
checks pass.

After explicit user approval, verified `440488f` is merged as `a4a3324` with the
strict protected quality gate enforced. [Main's complete workflow](https://github.com/vamegah/order-management-platform/actions/runs/42443556747)
also passes all twenty jobs on its first attempt, with the same 1,192 reactor
and 21 deployed cases. The required ten-case worker and five-case transport
reports are checked explicitly. Main's exact source tree equals the PR's tested
source tree. Source-bound reports, workflow logs and merge/protection records
are retained separately:

- `coverage/recovered-worker-failure-acceptance-ci-evidence.zip` and JSON:
  SHA-256 `d87ddf2f363d362744f6f14113cd121c03ef0e522d851d157b20973aa346ba1f`.
- `coverage/recovered-worker-failure-acceptance-main-ci-evidence.zip` and JSON:
  SHA-256 `420635445c4752c043663fec8136eab80debd7b417da5714e59561cebf2e1fd4`.

This accepts the bounded transaction/retry fixture. Actual process termination,
elapsed leases and scheduler/full-downstream recovery were outside that change.

## Worker process termination and elapsed lease continuation (2026-11-07)

A separate test-only follow-up adds `CheckoutRecoveryProcessIT`. PostgreSQL uses
the production V7/V8 schema, and loopback generated RPC endpoints keep synthetic
remote state. The actual worker runs in separate JVMs with proxied journal and
cleanup transactions. The fixture release changes remote state but withholds
the reply; the requesting JVM is forcibly terminated. A replacement JVM leaves
the live durable lease and attempt count unchanged. The test observes
PostgreSQL's real clock until the original two-minute lease expires, without
editing lease or retry timestamps. Fresh JVMs acknowledge the released resource
and preserve completion without repeating the mutation. Durable completion,
attempt/lease fields, metrics, forwarded identity and RPC transaction boundaries
are asserted. Child processes/channels are bounded and cleaned up.

Docker becomes available during this continuation. The seven-module focused
`package failsafe:integration-test failsafe:verify` run passes all fourteen cases
(three targeted worker unit and eleven PostgreSQL integration), with no failures,
errors or skips. The new process method takes 166.207 seconds; the ten previous
worker integration cases also pass. Log: `recovery-process-postgres-verification.log`
(06:55, exit 0). Formatting/compilation and separate JVM-launch preflight pass;
the launcher supports the actual 50,730-character classpath and paths with
spaces using a Java argument file. Exact-source reports and logs are retained
in `coverage/checkout-recovery-process-local-evidence.zip` and JSON.

This command does not run full-reactor or combined coverage acceptance. Publication
and full CI execution remain required: 1,193 reactor cases (including the new
process case and ten worker cases) and 21 deployed service/client cases. The
fixture invokes ticks directly and uses plaintext loopback RPCs and synthetic
credentials. Scheduler restart, a full application container crash, downstream
restart, payment/refund crash points, complete historical discovery and
accepted-order/payment expiry coordination remain open. Recovery and expiry
remain disabled; parent lifecycle tasks remain unchecked.

## Worker process PR and default-branch acceptance

[PR #12](https://github.com/vamegah/order-management-platform/pull/12) subsequently
passes all 1,193 reactor cases (918 unit and 275 integration), 21 deployed
service/client cases, two packaged payment-to-webhook cases, seventeen client
transport cases and 32 contract-job cases. Required JUnit reports contain no
failures, errors or skips. All fourteen exact-base unit coverage comparisons
pass with unchanged production instruction counts; order remains 4,450
covered/3,021 missed. All fifteen static reports, 211-dependency vulnerability
policy, ten image builds and five isolated issuer checks pass.

With explicit user approval, verified `388cbeb` is merged as `f2fdd02` under
strict protected merge checks. [The complete main workflow](https://github.com/vamegah/order-management-platform/actions/runs/42446674355)
passes all twenty jobs on its first attempt with the same 1,193 reactor and
21 deployed cases. The required process case executes for 125.307 seconds in
PR CI and 124.870 seconds on main, above the explicit 120-second minimum.
The ten worker and five deployed transport cases are also checked explicitly.
Main's exact source tree equals the PR's tested tree. Source-bound reports,
workflow logs and merge/protection records are retained separately:

- `coverage/checkout-recovery-process-ci-evidence.zip` and JSON:
  SHA-256 `e536dc6a54a30128f415fe2f8959986a0c3572749c6a4a644fba26cb9a94621d`.
- `coverage/checkout-recovery-process-main-ci-evidence.zip` and JSON:
  SHA-256 `cd98a95ff36455a56aec2eb5dfcc773b73d76ef4c8810e717b9b27f329012af7`.

This accepts actual worker-JVM termination and elapsed cleanup lease recovery
for the bounded loopback fixture. Scheduled application/container restart,
downstream-service restart and additional payment/refund crash points remain open.

## Recovery compensation transport specifications (2026-11-07)

Two additional `RecoveryTransportIT` cases specify compensation with a real
projected workload JWT over the existing mTLS channels. They create resources
for fresh orphan UUIDs, validate returned ownership/identities and then repeat
reservation release/pending cancellation or a confirmed payment's remaining
refund. Refund assertions require USD 12.34, completed status, one refund identity
and unchanged persisted payment/refund history under the same idempotency key.
Cleanup uses only verified test-owned IDs. The dedicated development stack
selects the deterministic `noop` refund provider.

The focused four-module formatting/test-compilation build passes from the final
source (`compensation-transport-compilation.log`, 24.109 seconds, exit 0). The
seven-case e2e class compiles and remains required by e2e evidence discovery.
The two new deployed cases have not executed locally; this is compile evidence,
not deployed acceptance. Exact source, compiled class and successful log are
retained in `coverage/recovery-compensation-transport-local-evidence.zip` and JSON.
Publication and full CI must execute all seven transport cases within 23 deployed
service/client cases. The default reactor remains at 1,193 cases.

The specifications invoke RPCs directly. Scheduled worker deployment, checkout
failure injection, downstream restart, production provider behavior, complete
historical discovery and accepted-order/payment expiry coordination remain open.
This follow-up changes tests and documentation. Recovery and expiry remain
disabled and parent lifecycle tasks stay unchecked.

## Compensation transport PR and main acceptance (2026-11-08)

[PR #13](https://github.com/vamegah/order-management-platform/pull/13) passes its
[complete workflow](https://github.com/vamegah/order-management-platform/actions/runs/42482612433) at `bcdf4e9`: 1,193 reactor cases (918 unit and
275 integration), 23 deployed service/client cases, two packaged payment journeys,
seventeen client transport and 32 contract-job cases. All fourteen exact-base
unit coverage comparisons pass with unchanged production counts. Fifteen static
reports, the 211-dependency policy, all ten images and five issuer checks pass.

With explicit approval it is merged as `7114cb1` under strict protected checks.
[The complete main workflow](https://github.com/vamegah/order-management-platform/actions/runs/42484444407) passes all twenty jobs on its first
attempt with the same 1,193 reactor and 23 deployed cases. JUnit evidence checks
all seven transport cases, including both new compensation methods, ten worker
cases and the original process case (126.633 seconds in PR CI and 126.723
seconds on main). Required reports have no failures, errors or skips. The merged
source tree equals the reviewed tree, and its parents match the approved base
and head. Reports, workflow logs and source/merge/protection records are retained:

- `coverage/recovery-compensation-transport-ci-evidence.zip` and JSON:
  SHA-256 `e327a3fecc013f96b96b7e8e5f964ef6288b175b1cb5e70054cfcb35316ba4e4`.
- `coverage/recovery-compensation-transport-main-ci-evidence.zip` and JSON:
  SHA-256 `a74161912e1271a840fbb7b68893b2d18d1ea9f8b6d94261eefe0b8afbea7fd0`.

This accepts direct projected JWT/mTLS compensation against the development noop
refund provider. Scheduled/container/downstream restart, production-provider
behavior, further crash points and accepted-order expiry coordination remain open.

## Payment compensation process crash specifications (2026-11-08)

Two additional `CheckoutRecoveryProcessIT` invocations kill the real worker JVM
after a fixture payment cancellation or remaining-balance refund succeeds but
before its response is acknowledged. Replacement JVMs respect the original
cleanup lease, wait on PostgreSQL's unmodified clock and acknowledge the terminal
payment without repeating compensation. The refund request must omit the amount
and retain the key derived from the durable task. Refund identity/amount, payment
state, durable attempts/leases, metrics, forwarded identity and RPC transaction
boundaries are checked through another fresh worker process.

Formatting, compilation, three focused worker unit and all thirteen PostgreSQL
cases pass without failures, errors or skips. The cancellation/refund process
methods take 138.277/134.130 seconds, and the release method takes 158.039 seconds;
each observes the unmodified lease deadline. Long-classpath JVM-launch preflight
also passes. Exact-source local evidence is retained in
`coverage/checkout-recovery-payment-crash-local-evidence.zip` and JSON. Complete
CI must execute all 1,195 reactor and 23 deployed cases before acceptance. This
changes tests and documentation only. The endpoints retain synthetic state in the parent
fixture; they do not restart or exercise a production refund processor. Scheduled
application/container recovery, downstream restart, additional crash points and
accepted-order expiry coordination remain open. Recovery and expiry stay
disabled by default, and no parent task is marked complete.

## Payment crash PR and main acceptance (2026-11-08)

[PR #14](https://github.com/vamegah/order-management-platform/pull/14) passes its
[complete workflow](https://github.com/vamegah/order-management-platform/actions/runs/42486543600) at `b31a5a8`: 1,195 reactor cases (918 unit and
277 integration), 23 deployed service/client cases, two packaged payment journeys,
seventeen client transport and 32 contract-job cases. All fourteen exact-base
unit coverage comparisons pass with unchanged production instruction counts.
Fifteen complete static reports, the 211-dependency policy, all ten images and
five source-bound issuer checks pass.

With explicit approval it is merged as `19c8032` under strict protected checks.
[The complete main workflow](https://github.com/vamegah/order-management-platform/actions/runs/42488828502) passes all twenty jobs on its first
attempt with the same 1,195 reactor and 23 deployed cases. Required JUnit reports
have no failures, errors or skips. Each of the three process cases exceeds its
explicit 120-second minimum. PR durations are 125.293, 124.904, 124.721 seconds;
main durations are 124.461, 123.681, 123.837 seconds. Ten worker and seven
transport cases are checked explicitly. The merged source tree equals the
reviewed tree, and its parents match the approved base and head. Reports,
workflow logs and source/merge/protection records are retained separately:

- `coverage/checkout-recovery-payment-crash-ci-evidence.zip` and JSON:
  SHA-256 `0ce16cbcbff00c3f01db265210c8aef92a925ba17e282e60169a64e67f4d3aec`.
- `coverage/checkout-recovery-payment-crash-main-ci-evidence.zip` and JSON:
  SHA-256 `92300f9551fff48a4817c971a4cf3afade3f9993586af1518069ec4b4f77367e`.

This accepts worker-JVM termination after reservation release, pending cancellation
and a completed fixture refund with real cleanup-lease expiry. The parent retains
synthetic downstream state. Scheduled application/container/downstream restart,
production-provider behavior and accepted-order expiry coordination remain open.

## Spring scheduler and context restart specifications (2026-11-08)

`CheckoutRecoverySchedulerIT` adds one default PostgreSQL case registering the
production recovery bean under real Spring scheduling. No test invokes worker
ticks. Invalid projections increment failure metrics without claiming the journal
or calling RPCs. Atomic replacements allow automatic reservation release and
payment cancellation. Closing the context must terminate the scheduler; a new
context against the same database then recovers another orphan with a new token.
Further ticks preserve completion without more RPCs or mutations. Assertions
cover acknowledged metrics, durable attempts/leases, forwarded identity, cleared
scheduler-thread scope and RPCs outside transactions. The fixture advances no
journal/cleanup timestamp.

The final seven-module formatting/compilation and focused package/Failsafe builds
pass all fourteen cases (three worker unit and eleven PostgreSQL), without
failures, errors or skips. The new method runs for 12.795 seconds; its metric
waits include acknowledgment and its shutdown assertion checks executor termination.
Exact-source reports, compiled classes and logs are retained in
`coverage/checkout-recovery-scheduler-local-evidence.zip` and JSON. Complete PR CI
must execute 1,196 reactor and 23 deployed cases before acceptance.

This changes tests and documentation. Synthetic in-process endpoints and same-JVM
Spring contexts bound the scope. Full Boot/container/JVM or downstream restart,
deployed scheduler identity, production-provider behavior and accepted-order
expiry coordination remain open. Default workers and expiry stay disabled; parent
lifecycle tasks remain unchecked.

## Spring scheduler PR and main acceptance (2026-11-08)

[PR #15](https://github.com/vamegah/order-management-platform/pull/15) passes all
1,196 reactor cases, 23 deployed service/client cases, fourteen exact-base
coverage comparisons and every required CI gate. With explicit approval it is
merged as `16734d4`. [The complete main workflow](https://github.com/vamegah/order-management-platform/actions/runs/42534234966)
passes all twenty jobs on its first attempt with the same case counts. Required
JUnit suites include one scheduler, ten worker, three process and seven recovery
transport cases, with zero failures, errors or skips. Each process case exceeds
120 seconds. The merged tree equals the reviewed tree and its parents match the
approved base and head. Strict quality checks and administrator enforcement are
verified again. Evidence includes exact source, full logs, artifacts and approved
merge/ready/protection records:

- `coverage/checkout-recovery-scheduler-ci-evidence.zip` and JSON: SHA-256
  `45df1f01f68e5d87516cbd8537859d8659cbba31fc0514376cf12af3d39c046b`.
- `coverage/checkout-recovery-scheduler-main-ci-evidence.zip` and JSON: SHA-256
  `5fcd446a9d9e69427482986223431eee097861d18a4c6777c883d8a256294c0e`.

This accepts the bounded Spring-context scheduler fixture. Deployed Boot worker,
container/downstream restart, production identity/provider behavior and accepted-
order expiry/compensation remain separate acceptance. The new supervised deployment
implementation is local follow-up work; parent lifecycle checkboxes stay open.

## Supervised deployed checkout recovery (2026-11-08)

The first batch from the implementation plan adds an explicit development
projector container, running as UID/GID 1000 with a read-only root filesystem,
dropped capabilities, bounded resources and a projection health check. Its
private credential is read-only; its token directory is writable by the projector
and read-only in order-service. Initial health and Kafka dependencies support
fresh deployment. Default workers and inventory expiry remain disabled.

A separate `recovery-e2e` profile and opt-in acceptance overlay execute the real
scheduled Boot application against independently packaged inventory/payment
services, PostgreSQL and the development issuer over original JWT/mTLS. The
ordinary suites run first. Required CI then executes both recovery cases and
rejects missing/skipped or incomplete evidence.

The final four-module Linux verification passes **70 unit and two deployed
cases**, with no failures, errors or skips (11:13 total). The first method takes
298.571 seconds: malformed projection exclusion, order/inventory/payment restart,
automatic reservation release and confirmed-payment refund, active-order
exclusion, supervised token renewal and pending-payment cancellation on existing
application/channels. The second takes 307.516 seconds: inventory outage, durable
failed discovery, application restart, unchanged resources during persisted
backoff and automatic cancellation/release after the real five-minute deadline.
No worker tick or database eligibility timestamp is changed by the tests.

All 59 Python tooling tests pass, including both new profile-discovery checks.
Nine resolved Compose checks, four restart guard preflights and an actual
UID-1001 Compose client mounting a UID-1000 credential through a mode-0700 parent
also pass. Runtime checks retain five matching deployed JAR hashes, image
identities and public TLS hashes without credentials. Reports, compiled fixtures,
logs, exact reviewed source and metadata are retained in
`coverage/scheduled-recovery-deployment-local-evidence.zip` and JSON.

The first Windows client attempt received a different certificate through the
host forwarding path. The Linux client verifies the original CA/client identity
inside the isolated networks; verification is never disabled. An initial Linux
attempt exposed the confirmation response's pre-flush updated-at timestamp in
the fixture. Final comparisons use committed snapshots and wait for metric
acknowledgment. A later restart run exposed an ingress readiness race and cleanup
that could mask the original failure. The final fixture waits for the public
order health route after restarts and preserves primary failures during cleanup.
Its token-renewal checkpoint starts after the first recovery on established
application channels. Earlier attempt evidence remains separate from final results.

Full hosted CI for this follow-up remains required: 1,196 default reactor cases,
25 deployed service/client cases including both new methods, and every unchanged
quality gate. The local run covers the development refund provider, supervised
identity and controlled container failures. Full checkout failure injection,
accepted-order expiry/partial-fulfillment compensation, historical ledger
completeness and production identity/provider acceptance remain open. Parent
lifecycle items remain unchecked.

## Supervised recovery PR and main acceptance (2026-11-08)

[PR #16](https://github.com/vamegah/order-management-platform/pull/16) passes all
twenty jobs at `a3508d4`, including 1,196 reactor cases and 25 deployed
service/client cases. The initial workflow validator rejected runner context in
job environment values; the workflow-only correction defines private paths in
a runner step and passes actionlint. No Java or deployment behavior changed in
that correction.

The repository owner merged the PR as `a3e4b85`.
[The complete main workflow](https://github.com/vamegah/order-management-platform/actions/runs/42544792059)
passes all twenty jobs and the same case counts. Both runs execute all required
worker, process, scheduler and transport suites, plus the two new deployed
scheduled recovery methods. The PR discovery-outage method runs for 303.724
seconds; PR/main outage cases exceed 300 seconds and every process-crash case
exceeds 120 seconds. Merge parents, exact source tree, successful required check,
strict freshness and administrator enforcement are verified.

- PR source-bound CI archive: `coverage/scheduled-recovery-deployment-ci-evidence.zip`
  and JSON; SHA-256 `e850daf199187deed8fdc3b1cc9364821c76b9ddb20a872907c2ec12ef7ac421`.
- Main source-bound CI archive: `coverage/scheduled-recovery-deployment-main-ci-evidence.zip`
  and JSON; SHA-256 `7eab730b10072511c0d6fe2bc4d6650c22be251d1d728191f13d33841fbe2143`.

This accepts supervised development-provider recovery across projection renewal
and container/downstream restarts. It does not close historical completeness,
full checkout failure injection, accepted-order expiry/partial fulfillment or
production identity/provider acceptance. Parent lifecycle items stay open.

## Historical reservation reference census (2026-11-08)

Administrators can now traverse local order-line reference metadata through
`GET /api/v1/order-reservation-repairs/census`. The endpoint accepts an opaque
line UUID cursor and a limit of 1-100 (default 50). It scans healthy lines too,
returns the last scanned cursor when more rows exist, and reports missing or
shared references with their order/version/status and line coordinates.

The query uses prepared parameters, the line primary-key order and the existing
reservation index. A five-second read-only transaction and a `limit + 1` result
bound keep requests bounded. It does not call remote inventory, mutate orders,
allocate holds, append repair audits or publish events. Only the existing
administrator authority can use it. See [the operator procedure](order-reservation-repair.md#census-missing-and-shared-references).

Clean unit verification passes all 417 focused cases. Five unit instruction
comparisons against the source-equivalent accepted main revision pass; order
coverage increases from 59.563646% to 60.730534%. New cases exercise lookahead
isolation, healthy/empty pages, shared references, invalid limits and malformed
cursors. PostgreSQL/HTTP cases check complete cursor traversal, metadata/event/
audit preservation and administrator-only access.

The final clean seven-module `verify` passes **505 cases: 417 unit and 88
integration**, without failures, errors, skips or missing suites (15:57 total).
The unchanged combined order instruction gate passes at **86.30%**. All three
process-crash cases still execute for at least 120 seconds. A separate clean
Linux compile/SpotBugs run produces six complete reports with zero findings,
errors or missing classes, using the unchanged generated-code-only filter.
Source hashes match the unit, full integration and static-analysis evidence.

Restored OneDrive cache metadata blocked Windows plugin resolution before test
execution in an earlier attempt. A checksum-verified parent POM replacement
restored clean unit verification; static analysis uses the separate Linux cache.
No test or coverage policy is weakened. Exact reviewed source, reports, compiled
fixtures, logs and metadata are retained in
`coverage/reservation-reference-census-local-evidence.zip` and JSON.

Full hosted CI must execute all 1,207 reactor cases, 25 deployed service/client
cases and every existing quality gate. This census is an inspection prerequisite
for task 1.1. A live scan is not a consistent historical snapshot or remote
ownership proof. Unresolved active/terminal rows, warehouse provenance, audited
historical disposition, repair/upgrade acceptance and final integrity constraints
remain open; no parent checkbox is changed.

## Historical census PR and main acceptance (2026-11-09)

[PR #17](https://github.com/vamegah/order-management-platform/pull/17) is merged
with explicit user approval as `7fc599d`. Its
[complete PR workflow](https://github.com/vamegah/order-management-platform/actions/runs/42566809401)
and [main workflow](https://github.com/vamegah/order-management-platform/actions/runs/42652620669)
pass all twenty jobs, 1,207 reactor cases and 25 deployed service/client cases.
The PR also preserves all fourteen exact-base unit coverage comparisons. Both
runs pass fifteen complete static reports, the 211-dependency policy, contracts,
packaged journeys, issuer checks and ten image builds. Crash cases exceed their
real 120-second leases and deployed outage recovery exceeds its 300-second retry
deadline. Main's merge tree equals the reviewed source. Required GitHub Actions
quality checks, strict freshness and administrator enforcement were read back.

Exact-source PR/main reports and approved merge/protection records are retained
in `coverage/reservation-reference-census-ci-evidence.zip` and
`coverage/reservation-reference-census-main-ci-evidence.zip`, with JSON hashes.
This accepts the census prerequisite; active historical ownership and disposition
remain separate from that merged inspection endpoint.

## Historical terminal exception policy (2026-11-09)

The maintainer explicitly approved audited exceptions for historical DELIVERED
and CANCELLED orders whose original reservation cannot be recovered. Active and
future accepted orders still require verified references; unresolved active lines
keep task 1.1 open. The implementation plan and backlog acceptance reflect this
specific policy decision.

The follow-up captures missing line identities at upgrade and exposes bounded
administrator inspection and recording under the existing repair namespace.
It requires a complete plan, expected aggregate version, reason and retained
evidence. A short transaction locks the order, rechecks terminal status and
eligibility, appends immutable application audit entries and increments the
version once. Exact retries return the original audit; conflicts, stale new
plans, changed identities and future lines are rejected. No stock, reference,
order status, payment, history or public outbox event is changed. The operator's
evidence is an attestation, not automated proof that a remote hold is absent.

Clean order/shared `verify` passes **548 cases: 452 unit and 96 integration**,
without failures, errors, skips or missing suites. It includes the V3-to-current
upgrade preserving all three seeded orders, four lines, status history and applied
checksums; active/future rejection; lost-reply concurrency; second-line rollback;
and real HTTP authorization/actor capture. Distinct OpenAPI component names and a
generated-schema regression preserve the existing repair line contract. All three
process-crash cases exceed their real 120-second leases.

The unchanged combined order instruction gate passes at **87.13%**. All five
focused unit comparisons pass, with order unit coverage increasing from
60.730534% to 62.130751%. Six complete exact-source Linux static reports contain
zero findings, errors or missing classes. Source hashes bind the unit, full and
static reports to the reviewed code; owned analysis clients/volumes and download
bridges are removed while the existing dependency cache is preserved. Evidence
is retained in `coverage/terminal-reservation-disposition-local-evidence.zip` and
JSON. Hosted CI still must execute 1,250 reactor and 25 deployed cases. Active
repair/ownership, complete deployed historical acceptance and final integrity
constraints remain open.

## Terminal-policy approved merge and main acceptance (2026-11-09)

The user approved ready/merge/main verification for `03a6e70`. PR #18 is merged
as `93df5e3`, with the reviewed source tree `53ae3ef`. The initial push attempts
failed to create required jobs; the public summaries report GitHub internal
server errors. A fresh main workflow executed the reactor/deployed journeys but
marked several prerequisites abandoned. The required quality gate rejected them.
Retrying those jobs recovered all twenty checks on the unchanged source.

The recovered main workflow 42673767265 passes all 1,250 reactor cases and 25
deployed service/client cases, fifteen complete static reports, the dependency
policy, issuer checks and ten image builds. Source-bound main reports and the
preserved failures/retries are retained in
`coverage/terminal-reservation-disposition-main-ci-evidence.zip` and JSON
(SHA-256 `7be08bdca877bfa38a04be2a0e28580e2189dc6524295f1c38c2436a92ba8482`).
PR evidence is retained separately. Strict freshness, administrator enforcement
and the required GitHub Actions quality check were reverified after merging.
This accepts the terminal-policy prerequisite; active historical ownership,
deployed repair/restart acceptance and final integrity remain open.

## Forward reservation-reference guards (2026-11-09)

A separate implementation on top of approved PR #18 prevents orders with empty,
oversized, missing or internally shared references from moving through placement,
confirmation, shipment intent, shipment completion or delivery. The common
aggregate check precedes state/history mutation. Existing allowed cancellation
paths remain available for unresolved historical orders. No hold allocation,
legacy rewrite, remote ownership inference, contract field or migration changes
are introduced.

Twenty negative domain combinations, both valid size boundaries and three
historical cancellation states are covered. Ten real HTTP/PostgreSQL cases
compare full durable snapshots and fixture resource identities/states across
rejected retries, verify complete audited repair followed by placement and
confirmation, and retain missing metadata without an automatic terminal audit
after normal cancellation. Existing repair/version races now use legal
cancellation transitions while references remain unresolved. Focused preflight
passes all 114 cases. Clean complete verification passes 583 cases (477 unit and
106 integration) with no failures, errors, skips or missing suites. The three
real process-crash cases exceed 120 seconds each. Order combined instruction
coverage passes the unchanged gate at 87.33%; unit coverage increases from
62.130751% to 62.353368% and all five focused comparisons pass. Six complete
exact-source Linux static reports have zero findings, errors or missing classes.
Reports and hashes are retained in
`coverage/active-reference-guards-local-evidence.zip` and JSON. Full hosted CI
must still run all 1,285 reactor and 25 deployed cases. This follow-up remains
local until separate publication approval; its merge acceptance is separate.

Task 1.1 remains open for active historical ownership/warehouse provenance,
deployed repair/restart acceptance and final database integrity. This guard
prerequisite also supports 1.5; the complete transition/payment/refund race matrix
remains open.
