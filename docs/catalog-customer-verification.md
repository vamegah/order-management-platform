# Product and customer verification

The product and customer container suites run the production HTTP/security stack
against PostgreSQL 16.4, applying Flyway V1–V2 and validating the Hibernate schema.
Only the JWT decoder is replaced with deterministic test credentials. These tests
do not establish production issuer validation or a deployed Traefik journey.

## Repairs

- Each service enables JPA auditing once, through its existing JPA configuration.
  Customer datasource YAML indentation is valid.
- Product money maps to `price_amount` and `price_currency`. Product SKU/category
  slug and customer email map to the existing `citext` columns. Fixed-width
  country/currency mappings match PostgreSQL `bpchar`, without changing historical
  migrations.
- `app.cors` and `app.pagination` bind at the application root. The security chains
  process configured browser preflights before authentication. Shared CORS
  configuration coexists with unrelated MVC configurers; a service can replace
  it by declaring a bean named `sharedCorsConfigurer`.
  Customer name sorting uses the persisted `firstName`/`lastName` fields;
  unsupported `name` sorting returns 400 before querying the repository.
- Malformed JSON returns 400. Method-security denials return 403 through the
  problem-details handler.
- Customer address reads require an administrator or the matching verified
  `customer_id` claim, consistent with profile reads. A claimless customer's
  `/customers/me` response still obtains their profile through the subject link.
- Integration fixtures use Spring-owned container beans and random HTTP ports.
  Each test class receives a fresh context/database; CRUD tests create their own
  mutable products rather than changing shared seed assertions.

## Lifecycle behavior

Preference creation and replacement apply locale, currency and all three consent/
notification flags. Both paths serialize on the customer row. Setting preferences
does not add a new public HTTP endpoint.

Address creation, updates and removal serialize on the owning customer row.
Before promoting a default, the service flushes previous defaults of that type
as false, then promotes or inserts the chosen address. Both steps remain in one
transaction, preserving the immediate partial unique index and rollback behavior.
The managed address graph is flushed before returning a newly generated ID.
For address updates, `isDefault: false` clears the flag; an omitted flag preserves
it, including when moving an address to another type.

Product listings return active products when status is omitted or blank; explicit
status filters retain their existing semantics. Image replacement flushes new
image identifiers before returning the updated DTO, and orphan removal deletes
the prior image rows.

The lifecycle suites use independently committed calls to verify profile/email
updates, preference upserts, identity-link conflicts, default-address replacement,
concurrent changes, rollback, image replacement, category hierarchy and filtering.
They also exercise HTTP profile/address mutation responses and persisted identity
and foreign-key ownership across separate database transactions.

Spring manages container beans before dependent beans and stops them after those
beans, keeping the connection pool and database lifetimes aligned. See the
[Spring Boot Testcontainers reference](https://docs.spring.io/spring-boot/3.3/reference/testing/testcontainers.html).

## Commands

Use Java 21 and a reachable Docker engine. From the repository root:

```sh
./mvnw --batch-mode --no-transfer-progress --fail-at-end \
  -pl product-service,customer-service -am clean verify
```

For diagnosis without the lifecycle's packaging/coverage checks:

```sh
./mvnw --batch-mode --no-transfer-progress --fail-at-end \
  -pl product-service,customer-service -am \
  test-compile surefire:test failsafe:integration-test failsafe:verify
```

The diagnostic command does not satisfy the unchanged 80% service coverage gates.
Unit reports are in `target/surefire-reports`, integration reports in
`target/failsafe-reports`, and coverage reports in `target/site/jacoco`.

The new HTTP boundary cases exercise batch ordering/deduplication, missing and
malformed IDs, request bounds, invalid bearer tokens, configured sorting, CORS
origin allowlists, and customer ownership. Complementary MVC tests verify the
production security chain without requiring a database.

Full reactor verification, deployed/e2e journeys and remaining backlog acceptance
are tracked in [the implementation report](task-implementation-report.md).

## Latest local result

The clean unit/static/package build passes 125 tests and SpotBugs with zero
findings/errors. The subsequent five-module `verify` passes those tests and all
98 PostgreSQL-backed integration tests (49 product, 49 customer), with no missing
or skipped suites. Both unchanged 80% service instruction-coverage gates pass:
product 91.64%, customer 91.51%, applying the existing class-file exclusions.
Shared coverage gates pass too. This supersedes the earlier focused run blocked
at product 0.68/customer 0.49.

Evidence is in `catalog-lifecycle-clean-quality.log`,
`catalog-lifecycle-final-verify.log`, `catalog-lifecycle-final-evidence.log` and
`catalog-lifecycle-policy-evidence.log`. The source-to-report audit confirms every
selected suite executed, and packaged changed classes/configuration/migrations
match the build/source. All ten executable-package layer checks pass.

The unit-only snapshot is `coverage/after-catalog-lifecycle.json`, saved before
integration execution. It combines current changed-module reports with previous
unchanged-module reports (638 tests), rather than a new seventeen-module run.
All thirteen local regression comparisons pass against
`coverage/after-catalog-customer.json`. Lifecycle reports subsequently combine
unit/integration coverage and must not replace the unit-only measurement.

Full reactor verification, remaining container suites and deployed/e2e journeys
remain open.
