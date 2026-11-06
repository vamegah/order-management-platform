# Shared module audit

> **Status:** Implemented locally; PostgreSQL/Kafka deployment acceptance pending
> **Last reviewed:** 2026-10-29

Work on the broader backlog was held while all four shared modules were audited.
The initial clean shared build passed, but proto tests were disabled and several
runtime defects were outside existing test coverage.

## Domain

- Money factories and arithmetic could create amounts that overflow `numeric(19,4)`.
  Validation now checks integer bounds before scaling and checks rounding overflow.
  Extremely large positive exponents fail before large intermediate allocations;
  tiny exponent-form amounts round to zero without constructing huge powers of ten.
- Phone validation counted punctuation toward its minimum length and allowed
  embedded whitespace controls. It now requires seven actual ASCII digits and
  accepts only the documented formatting characters.
- Email validation accepted leading/trailing/repeated local dots, empty domain
  labels and labels beginning/ending with a hyphen. These cases and local/label
  length violations now fail while existing normalization remains in place.
- Removed unresolved cross-module entity references from value-object Javadocs.

Domain remains independent of Spring. Inputs previously accepted despite violating
these documented/storage invariants now throw validation exceptions before use.

## Common web

- Problem factories and correlation filters were conditional on a tracing bean.
  They now work without tracing, using optional trace enrichment when available.
- Component annotations on auto-configured utilities could interfere with bean
  overrides under component scanning. Utilities now use the existing conditional
  Boot bean registrations consistently.
- Request IDs were reflected without bounds, existing MDC values were discarded,
  and a trace lookup failure occurred outside the cleanup block. IDs are now
  bounded ASCII tokens, invalid IDs become UUIDs, and owned MDC entries are restored
  through all failure paths. Unrelated MDC entries remain available.
- Sort parsing discarded trailing empty tokens and could turn a comma-only sort
  into an unsorted query. Empty tokens, duplicate fields and oversized expressions
  now fail; defaults are checked against the same allowlist as explicit fields.

The correlation-header policy and default-sort allowlist requirement are documented
in [the shared module guide](../shared/README.md).

## Events

- `EventSerde.deserializer()` always threw. It now supports all sixteen canonical
  record types through an explicit class whitelist, validates required envelope
  metadata, preserves tombstones and ignores additive fields. Malformed, unknown
  or unsupported typed events use Kafka serialization errors.
- Canonical JSON omitted the interface's derived aggregate ID. It is now an
  explicit additive field. The envelope reader also derives IDs from older flat
  records and rejects invalid metadata/data shapes instead of coercing them.
- The outbox writer documented transaction requirements without enforcing them.
  It now uses mandatory transaction propagation and a direct-call guard against
  missing/read-only transactions. Required row metadata is checked against storage
  bounds before repository writes.
- Outbox acknowledgement waits were unbounded and interruption was swallowed.
  Waits now have a configurable timeout; interrupt status is restored and the rest
  of the batch remains pending. Terminal row transitions and retry settings are
  checked. Repeated publication marking retains its original timestamp.
- The legacy publisher discarded the future containing asynchronous failure
  callbacks. It now awaits bounded acknowledgement and reports failures to callers.
  Its after-commit helper distinguishes a real transaction from synchronization-only
  contexts. These helpers remain non-durable; business producers should use outbox.
- Topic provisioning was not registered by shared auto-configuration and its custom
  client ignored application security settings. It is now registered and delegates
  topic management to the configured KafkaAdmin. Invalid topic specifications fail
  before broker calls. Provisioning now runs when enabled; operator-managed
  deployments can set `omp.kafka.provision-topics=false`.
- Shared events now has an 80% instruction coverage gate without production-class
  exclusions, replacing the earlier absence of a measured coverage policy.

Timeouts or crashes can still cause at-least-once duplicate delivery. Consumers
must deduplicate; this work does not establish global ordering across workers,
exactly-once delivery or deployed database/broker acceptance. See
[the event module guide](../shared/events/README.md) for configuration and scope.

## Proto

- Removed the explicit Surefire skip and added generated-client/compatibility tests.
- Generation now writes an import-inclusive FileDescriptorSet and attaches a
  `protobin` Maven artifact for tooling.
- A frozen v1 descriptor baseline guards existing field names/numbers/types,
  cardinality, JSON names, presence, enum values/defaults/reservations, Java options
  and RPC input/output/streaming modes. Negative tests prove renumbering and streaming
  changes fail; additive changes remain allowed.
- Generated-client tests verify unary/client-streaming/server-streaming descriptors,
  exact monetary strings, and unknown field/enum preservation. No existing proto
  field number, package or RPC signature was changed.
- Repaired the malformed Markdown/code examples in the shared and module guides.

The current schemas have no bidirectional RPC. Generated builders do not validate
business inputs or provide server authentication, deadlines or backpressure. See
[the protocol guide](../shared/proto/README.md).

## Verification evidence

The initial clean baseline is recorded in `shared-baseline-verification.log`.
The subsequent clean shared-module build, coverage checks and SpotBugs run passed
in `shared-final-verification.log`. The final money exponent-boundary follow-up
also passed its tests, coverage gate and SpotBugs check in
`shared-domain-final-verification.log`.

| Module | Passing tests | Instruction coverage | Required coverage |
|--------|--------------:|---------------------:|------------------:|
| Domain | 37 | 97.35% | 90% |
| Common web | 32 | 100% | 85% |
| Proto | 5 | Generated code; compatibility tests | Not applicable |
| Events | 63 | 90.81% | 80% |

All **137 shared tests** pass. The complete 17-module reactor packaged successfully
with **408 unit and in-process tests**, zero failures, errors or skips
(`shared-consumer-regression.log`). This includes dependent-service regressions;
it does not include container integration suites. Focused behavior and protocol
runs are in `shared-behavior-verification.log`, `shared-events-verification.log`
and `shared-proto-verification.log`. See also
[the implementation report](task-implementation-report.md#shared-module-repairs).

The original shared audit did not execute container tests. Docker Desktop is now
reachable; subsequent PostgreSQL payment locking/rollback tests are recorded in
[the implementation report](task-implementation-report.md). Broker
TLS/SASL/topic-management journeys remain unverified.

The product/customer continuation also repairs shared CORS registration: the
configurer now backs off only for an explicit `sharedCorsConfigurer` bean, rather
than for every unrelated MVC configurer. Regression tests cover coexistence,
intentional replacement and real security-chain preflights. See
[product/customer verification](catalog-customer-verification.md).
