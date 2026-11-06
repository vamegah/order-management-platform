# Verification phases

Run `./mvnw clean test` for the complete unit/in-process test phase. Surefire discovers `*Test` and `*Tests`. These tests include embedded HTTP and in-process gRPC contracts but do not require Docker.

Run `./mvnw clean verify` for unit and integration verification, including coverage/formatting gates configured by each module. Failsafe discovers `*IT`; these tests require Docker for PostgreSQL/Kafka and other integration dependencies, including the self-owned packaged payment/notification journey. The `e2e` tag is excluded by default because those suites require an externally running stack. CI continues to run `clean verify`, so moving container-backed tests to the IT naming convention does not remove them from the required build. Follow a successful clean verification with `python scripts/check_verification.py` to reject missing/skipped suites and missing coverage evidence.

The product/customer application-context smoke tests and the notification outbound webhook journey now use `*IT` names. The customer smoke test was also renamed to identify its actual service. A clean build is required after this rename to remove stale test class files.

For a specific container suite, run from the root, for example:

```sh
./mvnw -pl order-service -am verify -Dit.test=OrderPersistenceIT,CheckoutJournalIT -Dfailsafe.failIfNoSpecifiedTests=false
./mvnw -pl payment-service -am verify -Dit.test=PaymentCreationConcurrencyIT -Dfailsafe.failIfNoSpecifiedTests=false
```

The e2e profile and fresh Compose journeys remain separate release acceptance work. `-Pe2e` includes tagged e2e suites alongside untagged integration suites; it no longer selects only e2e and silently omits the latter. Use `python scripts/check_verification.py --include-e2e` afterward. A successful unit phase is not evidence that container integration, deployment, provider, or e2e acceptance has passed. The implementation report records actual executed commands and limitations. See [quality gates](quality-gates.md) for the independent static-analysis, dependency policy, SBOM and image jobs.

The self-owned `PaymentToWebhookContainerIT` journey packages payment and
notification into separate Java containers and supplies its own infrastructure
and signed JWT issuer. It can run without the external stack using the focused
command in [platform verification](platform-verification.md). CI requires that
focused journey independently; it does not satisfy the external order/realtime
e2e suites.

The packaged journey is included in normal `verify`; it is not tagged `e2e`.
The focused command merely selects it for quicker diagnosis and an independent
CI job.


The 2026-10-28 clean reactor unit run passed 259 tests across all 17 modules with zero failures/errors/skips. A subsequent inventory/dependency run passed all nine inventory and six shared-event tests, including two new retry-activation checks (261 distinct tests across both runs). These results do not satisfy the full `verify`/container/e2e acceptance gate.

Latest focused order continuation: a clean six-module build passes 261 unit and
in-process checks, followed by 42 real PostgreSQL/HTTP integration checks and the
existing 80% order coverage gate. The saved unit-only regression snapshot records
649 current reactor reports using explicit unchanged-module provenance; it is not
a fresh full-reactor execution. See [order verification](order-verification.md)
and the implementation report for the exact scope and logs.

The 2026-10-30 clean seventeen-module unit/package/static-analysis run passes
701 tests without failures, errors, skips or missing source suites. All fourteen
SpotBugs reports have zero findings/errors, all thirteen fresh local unit
coverage comparisons pass, and all ten executable packages pass layer
extraction. Focused gateway/inventory/payment integration reports are archived
separately (eleven/five/sixteen cases respectively); their sources match the
final rebuild. Notification/platform/full verify and e2e acceptance remain open.
See the implementation report for logs, snapshot provenance and configured
combined coverage results.

The external suites now use independent orders, committed downstream references,
refreshing development credentials and TLS-enabled clients. See [external e2e
setup and verification boundaries](external-e2e.md); deployment and execution
remain required before accepting that milestone.
