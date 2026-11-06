# Payment creation and transition contracts

Keyed payment creation is serialized across REST and gRPC instances using the same PostgreSQL transaction-scoped advisory lock. The lock is acquired before the idempotency lookup and held through the payment/outbox commit. Creation transactions explicitly use READ COMMITTED, and lock acquisition times out after five seconds. Hash collisions serialize unrelated keys but do not merge requests: identity checks use the full key and payment details. The unique index remains the final database guard. All writers must use this lock protocol; mixed-version rollout or external writers are not covered by the serialization guarantee.

A gRPC creation replay must preserve order ID, customer ID, currency, and exact monetary amount. A matching replay returns the existing ID without another creation event, including an existing terminal intent. Changed details return ALREADY_EXISTS. Blank gRPC keys remain optional and provide no replay guarantee; nonblank keys must be at most 255 characters and contain no control characters. REST charging requires a key and rejects changed payment/provider details using its existing HTTP 400 validation contract. Charging an existing gRPC-created intent binds its previously unset provider; subsequent replays cannot switch providers.

The protobuf money boundary accepts a positive plain decimal with at most 15 integer digits and four fractional digits, exact in the currency's minor units. USD fractional cents, fractional JPY, excess KWD precision, zero, exponent notation, unsupported currencies, and out-of-range values are rejected before persistence. This avoids rounding before validation. REST integer minor amounts are also checked against database precision before locking or persistence.

Payment unary RPC error callbacks explicitly map domain failures to stable status codes:

| Failure | gRPC status |
| --- | --- |
| Invalid UUID, amount, or other input | INVALID_ARGUMENT |
| Missing payment | NOT_FOUND |
| Reused creation key with changed details | ALREADY_EXISTS |
| Invalid lifecycle transition | FAILED_PRECONDITION |
| Optimistic update or lock contention | ABORTED |
| Missing authentication / access denied | UNAUTHENTICATED / PERMISSION_DENIED |
| Unexpected failure | INTERNAL |

Public descriptions exclude database/provider exception details. Matching confirmation replays require the same provider reference. Cancellation replays do not duplicate events, and a cancelled intent cannot subsequently confirm. HTTP payment concurrency failures return 409 with a generic retry message.

The in-process contract tests exercise these statuses and lifecycle replays. `PaymentCreationConcurrencyIT` uses real PostgreSQL/JPA and outbox rows to test simultaneous gRPC creation, cross-protocol creation, and conflicting replay rollback. It requires Docker and has not run in this environment. Run `./mvnw -pl payment-service -am verify -Dit.test=PaymentCreationConcurrencyIT -Dfailsafe.failIfNoSpecifiedTests=false`.

Refund admission now uses the same key-lock mechanism followed by a payment row lock. Provider calls run outside these transactions; durable refund intents, leased dispatch and correlated webhook completion are described in [refund operations](payment-refunds.md). New gRPC intents record the configured provider; existing unbound payments require an audit. Provider charge retries, refund scanning/operator reconciliation and deployed acceptance remain open.
