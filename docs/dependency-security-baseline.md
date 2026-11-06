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

The migration branch preserves the baseline on `main`. Dependency updates do
not establish acceptance until the complete test, coverage, packaging, image,
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
