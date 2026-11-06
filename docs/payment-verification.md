# Payment verification scope

Payment startup uses `payments` as both Flyway's and Hibernate's default schema.
This includes the shared outbox entity, whose table has no hardcoded schema.
The banking-adapter retry policy references the current
`com.example.omp.payment.client.BankingUnavailableException` class. Historical
Flyway migrations are unchanged.

## Contracts

- `PaymentFlowIT` boots the complete application with real PostgreSQL and Kafka
  containers. It exercises successful and declined development-provider charges
  and idempotent retries. Kafka's test heap is bounded at 128?256 MiB.
- `PaymentCreationConcurrencyIT` verifies committed cross-protocol payment
  creation, database/outbox atomicity and refund recovery/reconciliation against
  PostgreSQL.
- `PaymentHttpContractTest` uses standalone MVC controllers with mocked services
  to verify charge status/response mapping, customer identity propagation, money
  and timestamp serialization, raw webhook body/header forwarding, provider
  rejection, processing outcomes and redacted retryable errors. It does not
  exercise the production HTTP authentication chain.
- `PaymentEventStreamHandlerTest` checks all lifecycle states, order/customer
  filters, readiness, cancellation and removal of disconnected observers. Its
  observers are test doubles. Replica fan-out and deployed authorization remain
  separate acceptance work.
- `NoopPaymentProviderTest` checks the development provider's signature/parsing
  contract, retryable simulated errors and refund replay/lookup ledger behavior.
  The provider is a development fixture, not external-provider acceptance.

Existing tests additionally exercise real Stripe HMAC verification, signed raw
bytes, provider API contracts and asynchronous refund lifecycle behavior. See
[refund operations](payment-refunds.md) and
[reconciliation](refund-reconciliation.md) for recovery limits.

```sh
./mvnw -pl payment-service -am clean verify
```

The service retains its existing 80% instruction threshold. Executed counts,
configured coverage results and remaining full-reactor/e2e acceptance are tracked
in [the implementation report](task-implementation-report.md). These local
checks do not establish deployed issuer/mTLS policy, real provider delivery or
full-stack release acceptance.

Latest focused acceptance: the clean five-module run passes 326 unit/in-process
and sixteen PostgreSQL/Kafka integration cases without failures, errors or
skips. Payment contributes 221 unit cases and all sixteen integration cases; its
existing instruction gate passes at 82.39%. The implementation report records
the exact log and archived evidence.
