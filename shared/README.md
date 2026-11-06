# Shared modules

Four Maven modules provide reusable contracts and infrastructure. They share the
platform version and are built from the repository root.

| Module | Artifact | Responsibility |
|--------|----------|----------------|
| `domain` | `omp-shared-domain` | JPA value objects without Spring dependencies |
| `common-web` | `omp-shared-web` | REST pagination, problem responses, request correlation and CORS |
| `proto` | `omp-shared-proto` | Generated protobuf/gRPC clients, descriptors and v1 compatibility tests |
| `events` | `omp-shared-events` | Event contracts, Kafka serialization, topic declarations and transactional outbox |

## Domain invariants

`Money` uses nonnegative amounts, four decimal places and HALF_UP rounding.
Factories and arithmetic results must fit `numeric(19,4)`, including after
rounding; the maximum stored amount is `999999999999999.9999`. Addition and
subtraction require matching currencies. The JPA no-argument constructors are
for persistence, not application input.

`Email` trims and lowercases with `Locale.ROOT`, validates its existing ASCII
address format, and rejects invalid dots/domain labels and overlong local parts
or labels. This is a deliberately limited application format, not full RFC 5322.
`PhoneNumber` preserves supported formatting, requires at least seven actual
ASCII digits, allows an optional leading plus and ordinary spaces/parentheses/
dashes/dots, and rejects embedded control characters. It is not E.164 normalization.

## Web auto-configuration

Adding `omp-shared-web` registers pagination, problem factories, correlation and
CORS through Boot auto-configuration. Utilities do not require a tracing bean.
When tracing is present, problems and logs include its trace ID. Services can
override each bean; shared utilities are not separately component-scanned.

CORS properties belong under root `app.cors`, and the service security chain must
enable CORS to allow configured browser preflights before authentication. The
shared configurer coexists with other MVC configurers. Replace it explicitly with
a `WebMvcConfigurer` bean named `sharedCorsConfigurer`; other shared utilities
back off by bean type.

Request IDs must start with an ASCII letter/digit, contain only letters/digits
or `._:-`, and be at most 128 characters. Missing or invalid IDs are replaced by
a UUID. The response and in-request MDC use the same ID; the filter restores
previous request/trace/span values even when the chain or trace lookup fails.

Pagination defaults to page 0, size 20, maximum size 100 and `createdAt,desc`.
The default and explicit sort fields must be in `app.pagination.allowed-sort-fields`.
An explicit sort can be `name,asc,price,desc` or a final field without a direction
(default ASC). Empty tokens, unknown fields/directions, duplicates and expressions
over 1024 characters raise `InvalidPaginationException`; services map it to HTTP 400.
Keep `createdAt` in the allowlist if clients use the default sort.

## Protocols and events

See [proto](proto/README.md) for descriptor artifacts, generated-client tests and
compatibility policy. See [events](events/README.md) for event formats, Kafka topic
configuration and durable production. Service-specific domain entities, controllers,
authorization policy and database migrations remain in their owning services.

## Verification

Run from the repository root with Java 21:

```sh
./mvnw -pl shared/domain,shared/common-web,shared/proto,shared/events -am -Pstatic-analysis clean verify
```

This executes all shared tests, Spotless, SpotBugs and the coverage gates: domain
90%, web 85%, events 80% instruction coverage. Generated protobuf classes are
verified by protocol tests rather than a generated-code coverage percentage.
Shared unit/contract tests do not require Docker. Real PostgreSQL/Kafka deployment
acceptance remains separate; see [test phases](../docs/test-phases.md) and the
[shared audit report](../docs/shared-module-audit.md).
