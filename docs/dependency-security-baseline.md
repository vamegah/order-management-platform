# Dependency security baseline migration

Status: implementation under verification; not release acceptance.
Reviewed: 2026-11-06.

The [executed baseline workflow](https://github.com/vamegah/order-management-platform/actions/runs/42279516153)
passed all 999 Java cases, ten external journeys, two deployed client cases,
static analysis, the packaged payment journey and ten source image builds.
The NVD-backed dependency job completed analysis and rejected the old dependency
inventory under the unchanged CVSS >= 7 policy. Subsequent baseline repairs fix
the client fixture's first-connection budget and use the canonical Shibboleth
repository; the banking contract and client jobs now also execute successfully.

## Why this requires a generation upgrade

Updating individual Spring 3.3-era dependencies is insufficient. For example,
[CVE-2026-59282](https://spring.io/security/cve-2026-59282/) lists Spring Framework
6.2.20 as an enterprise-only fix and 7.0.9 as the open-source fix. The migration
therefore selects the compatible Boot 4.1 / Cloud 2025.1 generation, following
the [Boot migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
and [Spring Cloud compatibility table](https://cloud.spring.io/).

The initial migration was merged externally during verification. Its first repair
[PR #2](https://github.com/vamegah/order-management-platform/pull/2) was subsequently
closed without merging; further fixes remain under verification on `ci/security-baseline`.
Main requires the up-to-date GitHub Actions `quality-gate`, including for
administrators. Dependency updates do not establish acceptance until the complete test, coverage, packaging, image,
external journey, compatibility and vulnerability jobs pass on the new revision.

## Selected families

| Component | Migration selection |
| --- | --- |
| Spring Boot / Framework | Boot 4.1.2, its managed Framework 7.0.10 |
| Spring Cloud | 2025.1.4 |
| Spring Security / GraphQL / Web Services | Boot-managed 7.1.2 / 2.0.6 / 5.0.3 |
| gRPC / protobuf | 1.84.1 / 4.36.2, including regenerated clients |
| PostgreSQL / Flyway | 42.7.14 / 12.4.0 |
| Resilience4j | 2.4.0 with the Boot 4 adapter |
| WSS4J | 4.0.2 |
| Kotlin libraries | 2.4.21 family alignment |
| Springdoc | 3.2.0, compatible with Boot 4.1 |
| Swagger UI | 5.34.1, including DOMPurify 3.4.17 |
| REST Assured / schema validator | 6.0.1, aligned with Groovy 5 |
| Maven test runners | Surefire/Failsafe 3.5.6 for JUnit 6 |

Boot manages the updated Netty, Jackson, Kafka, logging and telemetry families.
Explicit old leaf versions must not override those families accidentally.
The unchanged protobuf descriptor and SOAP fixtures remain compatibility gates.

## Compatibility work

Service and test imports follow Boot's module layout. JPA specifications use
the explicit unrestricted specification instead of an ambiguous null overload.
SOAP configuration implements the current `WsConfigurer` interface. Docker and
package checks use the supported tools mode with launcher/layer extraction.

Existing application JSON contracts continue through Boot's Jackson 2
compatibility module and its `spring.jackson2` settings; HTTP converters select
that mapper explicitly. This retains the existing DTO, event and webhook
serialization behavior while using Boot-managed patched Jackson libraries.
The compatibility module is deprecated upstream, so a later Jackson 3 migration
must prove those wire contracts independently.

The dedicated `shared/grpc` module replaces the two starter auto-configurations
that reference removed security/health APIs. It retains the existing global
authentication and exception interceptors, preserves channel-health details and
orders the original metrics integrations after Boot 4's registry. Its three
focused cases pass on real in-process RPCs, including missing/invalid credentials,
worker-context cleanup, recorded server timers and configurable channel health;
the new module's 80% instruction gate passes.

Shared web provides optional Feign codecs using the application's mapper. All
36 shared-web cases pass, including a real HTTP round trip for UUID/time/decimal
records and generic response collections, custom-codec precedence and absence
of Feign. The existing 85% shared-web gate passes. Complete service and deployed
verification remains required.

The subsequent clean eighteen-module unit/package build passes all 776 cases
without failures, errors or skips (`boot4-complete-unit-package-verification.log`).
All ten executable packages pass real tools-mode launcher/layer extraction
(`boot4-packaging-verification.log`). This is unit/package evidence; container
integration, deployed journeys and the complete security gate on the migration
are still required.

## Verification policy

CVSS >= 7, scanner errors and incomplete evidence still fail. No broad package
exclusions, blanket CVE suppressions or coverage reductions are part of this
migration. Any scanner product-identification error must have a narrow,
documented correction supported by the vendor advisory and artifact contents.
Production promotion, data restore and certificate rotation remain separate
backlog acceptance items.

## First migration PR execution

The approved migration was published in [PR #1](https://github.com/vamegah/order-management-platform/pull/1).
Its [first verification run](https://github.com/vamegah/order-management-platform/actions/runs/42310116688)
passes all ten source image builds, complete static analysis, the client package
and exact-base protobuf/SOAP compatibility. The remaining failures are retained
as diagnostics; this run does not establish migration acceptance.

Real HTTP cases expose the old REST Assured 5.5/Groovy 5 incompatibility described
in the [upstream migration issue](https://github.com/rest-assured/rest-assured/issues/1846).
REST Assured and its schema validator are aligned to 6.0.1; an independent real
HTTP/JSON probe passes with the service's Groovy 5 classpath. The payment runtime
also needs its own Jackson 2 auto-configuration dependency, rather than relying
on the wider test classpath to provide its mapper.

Boot's tracing auto-configuration now requires its dedicated integration module.
The seven existing instrumented services include that module and bind their
collector endpoint, gRPC transport and resource attributes through the current
`management.opentelemetry` properties. Existing Prometheus and console/Logstash
outputs remain configured. HTTP verification includes W3C trace identity in an
error response and actual Swagger UI bundle routing.

The new dependency report rejects only the two DOMPurify findings in Swagger UI
5.34.0. The selected 5.34.1 webjar was inspected: both distributed JavaScript
bundles contain DOMPurify 3.4.17, the [vendor's patched version](https://github.com/cure53/DOMPurify/security/advisories/GHSA-mrv2-3pqr-j5jr).
The resource routing version is updated with the dependency. A fresh complete
scan remains required; the findings are not suppressed.

The first exact PR-base coverage comparison detects one fewer covered product
instruction after replacing the removed JPA specification API. A focused
invalid-status predicate regression extends the product unit suite and checks
that an unknown status still denies results. The comparison policy remains
unchanged.

The focused repair build passes all 83 product cases (32 unit and 51 real
HTTP/PostgreSQL integration), including trace identity and UI assets, with no
failures, errors or skips. Its existing 80% coverage gate and both shared-module
coverage gates pass (`boot4-product-http-migration-verification.log`). Complete
PR verification still determines acceptance of the repaired migration.

The subsequent [repair run](https://github.com/vamegah/order-management-platform/actions/runs/42312503607)
passes the NVD policy, exact PR-base coverage, contracts, client, static analysis
and ten source images. The downloaded scanner report covers 211 dependencies
with no remaining CVSS >= 7 findings; fifteen SpotBugs reports contain no
findings, errors or missing classes. Its complete reactor diagnostic isolates
the remaining errors to payment startup and the notification broker fixture;
948 other executed cases pass, with the dependent platform suite still missing.

Spring Security 7 detects two existing payment catch-all HTTP filter chains.
Payment's HTTP policy now resides solely in `PaymentSecurityConfig`; the gRPC
configuration retains its authentication manager/readers. The real RPC fixture
now loads both production configurations together and passes accepted, missing
and invalid credentials, health probes and worker-context cleanup. The obsolete
notification test override for the embedded broker's listener is removed so
the current KRaft fixture selects its compatible listeners and ephemeral ports.
Existing real database/broker cases and complete CI must pass before acceptance.

The clean five-module notification verification subsequently passes 214 cases:
36 shared-web, 63 shared-events, three shared-gRPC, 99 notification unit and all
13 PostgreSQL/Redis/Kafka/HTTP/STOMP integration cases. No case fails, errors or
skips; all four module coverage policies pass. Log:
`boot4-notification-broker-verification.log`.

The changed payment unit build passes its complete 222-case suite and shared
dependencies. Ten focused development-provider cases then pass, including two
new success/replay and decline contracts. Measured unit coverage over the same
production classes is 6,339 covered / 2,462 missed instructions, above the PR
base ratio of 6,368 / 2,488 after removing the redundant chain. This local
combined unit measurement is diagnostic; the required PR job still measures
both revisions from clean checkouts. Logs:
`boot4-payment-unit-coverage-verification.log` and
`boot4-noop-provider-contract-verification.log`.
